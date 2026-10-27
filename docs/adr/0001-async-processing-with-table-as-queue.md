# ADR-0001 — Processamento assíncrono usando a própria tabela como fila

**Status:** Aceito

## Contexto
Depois de criar uma solicitação, o serviço precisa chamar o provedor externo de ativação. O provedor pode ficar lento (o cenário de produção mostra a latência dele subindo de 400 ms para 3 s) ou indisponível. A solicitação gravada precisa ser enviada mesmo se a aplicação cair entre o commit e a chamada HTTP, e a chamada não pode acontecer dentro de uma transação de banco aberta.

## Decisão
O `POST /api/v1/requests` grava a solicitação como `PENDING` e responde `201` imediatamente. Um worker agendado busca as solicitações `PENDING` vencidas com `SELECT ... FOR UPDATE SKIP LOCKED` numa transação curta, marca como `PROCESSING` e faz commit. Depois chama o provedor fora de qualquer transação e grava o resultado numa segunda transação curta.

## Alternativas consideradas
| Alternativa | Por que não |
|---|---|
| Chamada síncrona no `POST` | A latência da API passa a depender do provedor. Threads e conexões ficam presas enquanto ele está lento. Se a aplicação cair depois do commit, o registro fica sem envio |
| `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` | Rápido de implementar, mas o evento fica só em memória: se a aplicação cair, ele se perde. Precisaria da mesma varredura no banco como rede de segurança, então a varredura já resolve sozinha |
| Broker (Kafka/RabbitMQ) + transactional outbox | É o padrão que eu usaria para notificar **outros serviços**. Para processar o próprio trabalho, adiciona infraestrutura, operação e um segundo ponto de falha sem ganho real nesta escala. Continua precisando do outbox para ser correto |

## Consequências
- (+) A API tem latência estável, independente do provedor.
- (+) Entrega *at-least-once* garantida pelo banco, sem infraestrutura extra.
- (+) `SKIP LOCKED` permite várias instâncias sem coordenação.
- (−) O consumidor precisa consultar o `GET` para saber o resultado. Evolução natural: publicar um evento `ActivationCompleted` (outbox + Kafka) ou chamar um webhook.
- (−) Latência de até um ciclo do worker (padrão 1s) antes da primeira tentativa.
- (−) O polling gera carga constante e leve no banco. O índice parcial sobre `PENDING` mantém essa carga desprezível.
- (−) At-least-once implica possíveis chamadas repetidas ao provedor. Isso é mitigado pela `Idempotency-Key`.
