# Requisitos

Especificação dos requisitos do serviço de ativação de produtos. Cada requisito tem um ID (`R<n>`) e critérios de aceite (`R<n>.<m>`) verificáveis. Os critérios são referenciados nos commits e nos nomes/descrições dos testes.

Legenda de origem: **[O]** obrigatório no enunciado · **[D]** diferencial no enunciado · **[P]** decisão própria derivada de outro requisito.

---

## R1 — Criar solicitação de ativação [O]

`POST /api/v1/requests`

| Campo | Regra |
|---|---|
| `externalId` | obrigatório; 1–64 caracteres; `[A-Za-z0-9._-]` |
| `customerId` | obrigatório; 1–64 caracteres; `[A-Za-z0-9._-]` |
| `productCode` | obrigatório; 1–64 caracteres; `[A-Za-z0-9._-]` |

- **R1.1** Dados válidos → `201 Created`, header `Location: /api/v1/requests/{id}` [D], corpo com o registro em status `PENDING`.
- **R1.2** Campo ausente, vazio, acima do tamanho ou fora do formato → `400` com a lista de campos inválidos.
- **R1.3** Corpo JSON malformado ou ilegível → `400`, sem detalhes internos do parser.
- **R1.4** A resposta contém pelo menos `id`, `externalId`, `customerId`, `productCode`, `status`, `createdAt`, `updatedAt`. Datas em UTC, ISO-8601 [D].
- **R1.5** A criação não espera a resposta do provedor externo (ver R5 e ADR-0001).

## R2 — Consultar solicitação [O]

`GET /api/v1/requests/{id}`

- **R2.1** Registro existente → `200` com o status atual.
- **R2.2** Registro inexistente → `404`.
- **R2.3** `id` que não é um UUID válido → `404`. Do ponto de vista do consumidor, o recurso não existe.
- **R2.4** Quando o status é `FAILED`, a resposta inclui `failureReason` (motivo sanitizado, sem detalhes internos).

## R3 — Listar solicitações [D]

`GET /api/v1/requests?status=&customerId=&page=&size=`

- **R3.1** Filtros opcionais por `status` e `customerId`, combináveis.
- **R3.2** Paginação com `page` (padrão 0) e `size` (padrão 20, máximo 100). Ordenação por `createdAt` decrescente.
- **R3.3** `status` inválido ou `size` acima do máximo → `400`.
- **R3.4** Os índices que sustentam as consultas estão justificados no `design.md`.

## R4 — Evitar duplicidade [O]

- **R4.1** O banco garante a unicidade de `externalId` com uma constraint `UNIQUE`. Não existe caminho no código que dependa apenas de verificar e depois inserir.
- **R4.2** Repetição com o mesmo `externalId` e o mesmo conteúdo (`customerId` e `productCode` iguais) → `200` com o registro existente. É um retry idempotente [D].
- **R4.3** Mesmo `externalId` com conteúdo diferente → `409`, com mensagem indicando o conflito [D].
- **R4.4** N requisições idênticas em paralelo resultam em **exatamente um** registro. Uma recebe `201` e as demais `200` com o mesmo `id` [D]. Teste contra PostgreSQL real (Testcontainers).
- **R4.5** Mesmo `externalId` já usado por **outro** sistema consumidor → `409` com mensagem genérica, sem devolver nenhum dado do registro existente (coerente com R8.4).

## R5 — Integração com o provedor externo [O]

`POST /provider/v1/activate` (simulado com WireMock)

- **R5.1** Toda solicitação criada é enviada ao provedor, mesmo que a aplicação caia entre o commit e a chamada [D].
- **R5.2** A chamada HTTP nunca acontece dentro de uma transação de banco aberta [D].
- **R5.3** Comportamento por retorno:

| Retorno do provedor | Novo status | Nova tentativa? |
|---|---|---|
| 2xx | `COMPLETED` | — |
| 400 e demais 4xx não listados | `FAILED` | Não |
| 401 / 403 (credencial do serviço recusada) | `PENDING` (adiado) | Sim, em intervalo fixo, sem contar tentativa |
| 408 e 5xx (exceto 503) | `PENDING` (agendado) | Sim, com backoff |
| Timeout de conexão ou de leitura, erro de I/O | `PENDING` (agendado) | Sim, com backoff |
| 429 / 503 | `PENDING` (agendado) | Sim, respeitando o `Retry-After` quando presente [D] |
| Limite de tentativas atingido | `FAILED` | Não |

- **R5.4** Timeouts de conexão e de leitura explícitos e configuráveis [D].
- **R5.5** Toda chamada envia `Idempotency-Key` igual ao `id` da solicitação. Um retry depois de um timeout não gera ativação duplicada no provedor [D].
- **R5.6** Backoff exponencial com jitter, teto configurável e número máximo de tentativas configurável [D].
- **R5.7** Solicitações presas em `PROCESSING` além de um tempo limite (a aplicação caiu durante a chamada) voltam a ser elegíveis para nova tentativa (reconciliação) [D].
- **R5.8** Várias instâncias da aplicação podem processar ao mesmo tempo sem enviar a mesma solicitação duas vezes em paralelo [D].
- **R5.9** Um circuit breaker protege o provedor: com a maioria das chamadas recentes falhando (5xx, timeout, credencial recusada), o worker para de chamá-lo por um tempo e depois testa com uma chamada. Com o circuito aberto, nenhuma solicitação gasta tentativa [D].

## R6 — Máquina de estados [D]

- **R6.1** Transições válidas: `PENDING → PROCESSING`, `PROCESSING → COMPLETED`, `PROCESSING → FAILED`, `PROCESSING → PENDING` (retry, ou adiamento quando o provedor recusa a credencial do serviço).
- **R6.2** Qualquer outra transição lança uma exceção de domínio. `COMPLETED` e `FAILED` são estados finais.
- **R6.3** As transições são feitas por métodos do domínio, sem setters livres. Há teste unitário sem Spring.
- **R6.4** Atualizações concorrentes do mesmo registro são protegidas por lock otimista (`@Version`).

## R7 — Tratamento de erros [O]

- **R7.1** Todas as respostas de erro usam `application/problem+json` (RFC 9457) com `type`, `title`, `status`, `detail`, `instance`, `correlationId` e, quando há tracing, `traceId` [D].
- **R7.2** Erros de validação incluem `errors: [{field, message}]`, em ordem estável (por campo e mensagem).
- **R7.3** Erro inesperado → `500` com mensagem genérica. Nenhum stack trace, nome de classe ou SQL é exposto. O detalhe vai para o log com o mesmo `traceId`.
- **R7.4** Erros de integração não chegam ao consumidor da API como erro HTTP, já que o processamento é assíncrono. Eles ficam refletidos no status (`FAILED` com `failureReason`) e nos logs e métricas.
- **R7.5** Banco de dados indisponível → `503` com `Retry-After`, em poucos segundos (timeout curto do pool), em vez de `500` após 30 s.

## R8 — Segurança [O: descrever · D: implementar]

- **R8.1** A API é um OAuth 2.0 Resource Server que valida JWT: assinatura (via JWKS), `iss`, `exp`/`nbf` e `aud`.
- **R8.2** Sem token ou com token inválido → `401`.
- **R8.3** Escopo `activation:write` para criar. Escopo `activation:read` para consultar e listar. Escopo insuficiente → `403`.
- **R8.4** Cada solicitação pertence ao sistema que a criou (claim `client_id`/`azp`). Um sistema não consulta nem lista solicitações de outro: retorna `404`, não `403`, para não revelar a existência do registro (OWASP API1:2023, BOLA).
- **R8.5** Corpo da requisição acima de 16 KB → `413`. Cada sistema consumidor tem um limite de chamadas por período; acima dele → `429` com `Retry-After`.
- **R8.6** Actuator `health` (liveness/readiness) é público. Os demais endpoints do Actuator ficam em porta de gestão separada e não são expostos publicamente.

## R9 — Observabilidade [D]

- **R9.1** Liveness e readiness via Actuator. A readiness depende do banco.
- **R9.2** Um `correlationId` é aceito do header `X-Correlation-Id` ou gerado. Ele vai para o MDC e para a resposta, é persistido com a solicitação e propagado na chamada ao provedor. O tracing distribuído (OpenTelemetry) acompanha a solicitação do `POST` até a chamada ao provedor num único trace, atravessando a etapa assíncrona.
- **R9.3** Métricas Micrometer/Prometheus: latência e resultado das chamadas ao provedor, transições por status e quantidade atual de solicitações em `PENDING` e `PROCESSING`.
- **R9.4** Logs estruturados em JSON quando a aplicação roda em container. Nenhum token ou credencial aparece nos logs.

## R10 — Build, execução e entrega [O]

- **R10.1** `./mvnw verify` compila e executa todos os testes a partir de um clone limpo (requer Docker para o Testcontainers) [D].
- **R10.2** `Dockerfile` multi-stage, imagem final JRE enxuta, usuário não-root, memória da JVM ajustada ao container, health check [D].
- **R10.3** `docker compose up` sobe a aplicação, o PostgreSQL, o mock do provedor e o servidor de autorização fake [D].
- **R10.4** Schema versionado com Flyway [D].
- **R10.5** GitHub Actions executa `./mvnw verify` a cada push e pull request [D].
- **R10.6** O README contém as seções obrigatórias: Como executar, Decisões técnicas, Cenário de análise (item 10), Produção, Pontos pendentes, Melhorias futuras e Uso de IA.
- **R10.7** Regras de dependência entre pacotes verificadas com ArchUnit [D].

---

## Fora de escopo (descrito no README, não implementado)

- Publicação de evento `ActivationCompleted` em broker (Kafka) via transactional outbox.
- Limite global de chamadas entre instâncias: responsabilidade do API Gateway (o limite da aplicação é por instância).
- Manifestos de Kubernetes e Terraform.
