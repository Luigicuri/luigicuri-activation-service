# ADR-0003 — Política de retry e semântica de falha

**Status:** Aceito

## Contexto
O provedor pode responder com sucesso, com erro definitivo (o pedido é inválido) ou com erro temporário (indisponibilidade, sobrecarga, timeout). O timeout é ambíguo: o provedor pode ter ativado o produto mesmo sem responder a tempo.

## Decisão

| Retorno | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| 2xx | `COMPLETED` | — | Ativação confirmada |
| 400 e demais 4xx | `FAILED` | Não | O pedido em si é rejeitado. Repetir o mesmo pedido não muda o resultado |
| 408, 500, 502, 504 | `PENDING` | Sim, com backoff | Falha do lado do provedor ou da rede, provavelmente passageira |
| Timeout / erro de I/O | `PENDING` | Sim, com backoff | Resultado desconhecido. É seguro repetir porque a `Idempotency-Key` impede a ativação duplicada |
| 429 / 503 | `PENDING` | Sim, respeitando o `Retry-After` | O provedor diz explicitamente quando tentar de novo |
| Limite de tentativas | `FAILED` | Não | Evita retry infinito. O registro fica com `failureReason` para análise |

- **Falha temporária volta para `PENDING`** com `attempts++` e `nextAttemptAt`, em vez de continuar em `PROCESSING` com um contador. Assim, `PROCESSING` significa só "chamada em andamento agora", e um `PROCESSING` antigo é sempre anomalia (a instância caiu), tratada pela reconciliação.
- **Backoff exponencial com full jitter:** `random(0, min(maxDelay, initial × multiplier^(attempt-1)))`. O jitter evita que muitas solicitações que falharam juntas tentem de novo todas no mesmo instante.
- **`Idempotency-Key = id` da solicitação** é enviada em toda chamada.
- **Reconciliação:** `PROCESSING` com lease vencido volta para `PENDING` e conta como tentativa.

## Alternativas consideradas
- **Retry em memória (Spring Retry / Resilience4j Retry) dentro da mesma execução:** prende a thread do worker durante o backoff e perde o estado se a aplicação cair. O retry persistido no banco sobrevive a reinícios.
- **Circuit breaker (Resilience4j):** útil quando o provedor está fora por muito tempo, para parar de gastar tentativas. Fica fora do escopo e é descrito no README. O backoff persistido já reduz a pressão sobre o provedor.

## Consequências
- (+) O comportamento de cada retorno é explícito e testável de forma unitária.
- (+) Os retries sobrevivem a reinícios e são distribuídos entre as instâncias.
- (−) Depende de o provedor honrar a `Idempotency-Key`. Esse contrato precisa estar acordado com ele.
- (−) `FAILED` por limite de tentativas exige ação operacional (alerta e reprocessamento manual). Isso fica como melhoria futura no README.
