# ADR-0002 — Duplicidade garantida por constraint única no banco

**Status:** Aceito

## Contexto
`externalId` identifica uma solicitação do sistema de origem, que faz retry. Duas requisições iguais podem chegar quase ao mesmo tempo, inclusive em instâncias diferentes da aplicação.

Verificar se existe e depois inserir não resolve. Duas transações concorrentes executam o `SELECT`, ambas não encontram nada e ambas inserem. Nenhum lock em memória (`synchronized`, mapa de chaves) resolve com mais de uma instância.

## Decisão
- Constraint `UNIQUE (external_id)` no PostgreSQL como única fonte de verdade.
- A aplicação tenta o `INSERT` direto. Se receber violação da constraint `uk_activation_request_external_id`, relê o registro existente, fora da transação que falhou, e decide a resposta:

| Cenário | Resposta | Justificativa |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo | `200 OK` com o registro existente | É um retry legítimo. Responder `409` obrigaria o cliente a tratar erro como sucesso. O `200` (em vez de `201`) indica que nada novo foi criado |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict` | Provável erro do sistema de origem (chave reutilizada). Aceitar mascararia o problema |
| Mesmo `externalId`, outro sistema consumidor | `409 Conflict` genérico, sem dados | Não revela o registro de outro dono (BOLA) |
| N requisições idênticas em paralelo | Uma `201`, as demais `200` com o mesmo `id` | Consequência direta das regras acima |

## Alternativas consideradas
| Alternativa | Por que não |
|---|---|
| `INSERT ... ON CONFLICT DO NOTHING` (query nativa) | Atômico e sem exceção, mas prende o código ao PostgreSQL e foge do Spring Data JPA. A diferença de desempenho é irrelevante aqui |
| Lock pessimista ou advisory lock antes de inserir | Contenção e complexidade sem necessidade: a constraint já resolve |
| `409` para qualquer repetição | Mais simples, mas ruim para clientes que fazem retry |

## Consequências
- (+) Correto com qualquer número de instâncias e sob qualquer concorrência.
- (+) O caso comum (`externalId` novo) custa um único `INSERT`.
- (−) O serviço de criação não pode ser `@Transactional` como um todo, porque a violação marca a transação como rollback-only. A releitura acontece fora dela.
- (−) A unicidade é global. Se dois sistemas de origem diferentes puderem gerar o mesmo `externalId`, a constraint precisaria ser `(client_id, external_id)`. Essa evolução está registrada no README.
