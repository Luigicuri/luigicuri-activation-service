# ADR-0003 — Política de retry e semântica de falha

**Status:** Aceito

## Contexto
O provedor pode responder com sucesso, com erro definitivo (o pedido é inválido) ou com erro temporário (indisponibilidade, sobrecarga, timeout). O timeout é ambíguo: o provedor pode ter ativado o produto mesmo sem responder a tempo.

## Decisão

| Retorno | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| 2xx | `COMPLETED` | — | Ativação confirmada |
| 400 e demais 4xx | `FAILED` | Não | O pedido em si é rejeitado. Repetir o mesmo pedido não muda o resultado |
| 401 / 403 | `PENDING` | Sim, a cada `credentials-rejected-delay` (1 min), sem contar tentativa | A credencial recusada é a do serviço. O erro é de configuração e afetaria todas as solicitações; elas esperam a correção em vez de falhar |
| 408 e 5xx (exceto 503) | `PENDING` | Sim, com backoff | Falha do lado do provedor ou da rede, provavelmente passageira |
| Timeout / erro de I/O | `PENDING` | Sim, com backoff | Resultado desconhecido. É seguro repetir porque a `Idempotency-Key` impede a ativação duplicada |
| 429 / 503 | `PENDING` | Sim, respeitando o `Retry-After` | O provedor diz explicitamente quando tentar de novo |
| Limite de tentativas | `FAILED` | Não | Evita retry infinito. O registro fica com `failureReason` para análise |

- **Falha temporária volta para `PENDING`** com `attempts++` e `nextAttemptAt`, em vez de continuar em `PROCESSING` com um contador. Assim, `PROCESSING` significa só "chamada em andamento agora", e um `PROCESSING` antigo é sempre anomalia (a instância caiu), tratada pela reconciliação.
- **Backoff exponencial com full jitter:** `random(0, min(maxDelay, initial × multiplier^(attempt-1)))`. O jitter evita que muitas solicitações que falharam juntas tentem de novo todas no mesmo instante.
- **`Idempotency-Key = id` da solicitação** é enviada em toda chamada.
- **Credencial recusada (401/403):** a solicitação volta para `PENDING` e devolve a tentativa (`postpone`). Voltar ao número de tentativa anterior é seguro para a detecção de resultado atrasado, porque a chamada que acabou de responder era a única com esse número. Cada recusa gera log `ERROR` e métrica própria para alerta.
- **Reconciliação:** `PROCESSING` com lease vencido é tratado como falha temporária: conta como tentativa e volta para `PENDING` com backoff, ou vira `FAILED` se o limite de tentativas foi atingido.

## Alternativas consideradas
- **Retry em memória (Spring Retry / Resilience4j Retry) dentro da mesma execução:** prende a thread do worker durante o backoff e perde o estado se a aplicação cair. O retry persistido no banco sobrevive a reinícios.
- **Circuit breaker (Resilience4j):** útil quando o provedor está fora por muito tempo, para parar de gastar tentativas. Fica fora do escopo e é descrito no README. O backoff persistido já reduz a pressão sobre o provedor.

## Consequências
- (+) O comportamento de cada retorno é explícito e testável de forma unitária.
- (+) Os retries sobrevivem a reinícios e são distribuídos entre as instâncias.
- (−) Depende de o provedor honrar a `Idempotency-Key`. Esse contrato precisa estar acordado com ele.
- (−) Com a credencial recusada, as solicitações ficam `PENDING` até a correção. Isso exige alerta sobre a métrica `credentials_rejected` e sobre o tamanho da fila (`activation.requests.active{status=PENDING}`).
- (−) `FAILED` por limite de tentativas exige ação operacional (alerta e reprocessamento manual). Isso fica como melhoria futura no README.
