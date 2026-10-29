# Design

Como os requisitos de [requirements.md](requirements.md) são atendidos. As decisões com alternativas relevantes estão em [`docs/adr/`](../adr).

## 1. Stack

| Item | Escolha | Motivo |
|---|---|---|
| Linguagem | Java 21 | LTS. Records, sealed interfaces e pattern matching em `switch` |
| Framework | Spring Boot 3.5.x | Linha 3.x pedida no enunciado (provável stack da contratante). O suporte OSS da 3.5 terminou em jun/2026: a migração para a 4.x está registrada como melhoria futura. Spring MVC + Spring Data JPA/Hibernate |
| Build | Maven com wrapper | Ferramenta usada pela vaga. `./mvnw verify` roda tudo |
| Banco | PostgreSQL 16 | Suporta `FOR UPDATE SKIP LOCKED` e índices parciais |
| Migrações | Flyway | Schema versionado. `ddl-auto=validate` |
| Cliente HTTP | `RestClient` (sobre o `HttpClient` do JDK) | Síncrono, timeouts explícitos, sem trazer WebFlux |
| Segurança | `spring-boot-starter-oauth2-resource-server` | Validação de JWT via JWKS |
| Testes | JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers, WireMock, Awaitility, ArchUnit | — |

## 2. Organização de pacotes

Um pacote por funcionalidade, com camadas leves dentro dele. Nada de ports & adapters formal (ver enunciado: "não use padrões sem necessidade").

```
com.luigicuri.activation
├── ActivationServiceApplication
├── request/                      # funcionalidade: solicitações de ativação
│   ├── api/                      # controller, DTOs (records), mapeamento
│   ├── application/              # casos de uso: criar, consultar, listar
│   ├── domain/                   # entidade, status, regras de transição, exceções de domínio
│   ├── persistence/              # repositório Spring Data
│   └── processing/               # worker agendado, política de retry, reconciliação
├── provider/                     # cliente HTTP do provedor + classificação do resultado
└── shared/
    ├── error/                    # @RestControllerAdvice → ProblemDetail
    ├── security/                 # SecurityFilterChain, extração do client id
    └── web/                      # filtro de correlação, limite de tamanho do corpo
```

Regras verificadas com ArchUnit (R10.7):
- `domain` não depende de `api`, `application`, `persistence`, `processing`, `provider`, nem de Spring Web.
- `api` não acessa `persistence` diretamente.
- `provider` não conhece a entidade JPA. Recebe e devolve tipos próprios.

## 3. Modelo de domínio

```
ActivationRequest (entidade JPA)
  id               UUID       gerado pela aplicação
  externalId       String
  customerId       String
  productCode      String
  clientId         String     dono (claim do JWT), R8.4
  status           ActivationStatus
  attempts         int        tentativas já feitas ao provedor
  nextAttemptAt    Instant    quando volta a ser elegível (status PENDING)
  processingStartedAt Instant início do lease (status PROCESSING), R5.7
  failureReason    String     sanitizado, só quando FAILED
  correlationId    String     traceId da requisição de criação, R9.2
  version          long       @Version, R6.4
  createdAt, updatedAt Instant UTC
```

Transições (R6): só por métodos de domínio. Cada um valida o estado atual e lança `IllegalStatusTransitionException` quando não pode.

```
             startProcessing()          complete()
  PENDING ───────────────────▶ PROCESSING ─────────▶ COMPLETED
     ▲                            │   │
     │ scheduleRetry(at)          │   │ fail(reason)
     │ postpone(at) (401/403)     │   │
     └────────────────────────────┘   └──────────────▶ FAILED
```

**Falha temporária: volta para `PENDING`** com `attempts++` e `nextAttemptAt` no futuro (ADR-0003). `PROCESSING` significa apenas "uma instância está chamando o provedor agora". Isso deixa a reconciliação trivial: `PROCESSING` com lease vencido é sempre anomalia.

## 4. Esquema (Flyway `V1__create_activation_request.sql`)

```sql
CREATE TABLE activation_request (
  id                    UUID PRIMARY KEY,
  external_id           VARCHAR(64)  NOT NULL,
  customer_id           VARCHAR(64)  NOT NULL,
  product_code          VARCHAR(64)  NOT NULL,
  client_id             VARCHAR(128) NOT NULL,
  status                VARCHAR(16)  NOT NULL,
  attempts              INT          NOT NULL DEFAULT 0,
  next_attempt_at       TIMESTAMPTZ  NOT NULL,
  processing_started_at TIMESTAMPTZ,
  failure_reason        VARCHAR(255),
  correlation_id        VARCHAR(64),
  version               BIGINT       NOT NULL DEFAULT 0,
  created_at            TIMESTAMPTZ  NOT NULL,
  updated_at            TIMESTAMPTZ  NOT NULL,
  CONSTRAINT uk_activation_request_external_id UNIQUE (external_id),
  CONSTRAINT ck_activation_request_status
    CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED'))
);
```

| Índice | Atende | Justificativa |
|---|---|---|
| `uk_activation_request_external_id` (único) | R4 | Garantia de unicidade. Também atende a busca por `externalId` no fluxo de repetição |
| `ix_ar_pending_due (next_attempt_at) WHERE status = 'PENDING'` | worker | Parcial: indexa só a fila, que é pequena. Os registros finais (a maioria) ficam fora |
| `ix_ar_processing_lease (processing_started_at) WHERE status = 'PROCESSING'` | reconciliação | Mesmo raciocínio: só os registros em andamento |
| `ix_ar_client_created (client_id, created_at DESC)` | R3 sem filtro | Toda listagem é por dono (R8.4). Ordenação já resolvida pelo índice |
| `ix_ar_client_status_created (client_id, status, created_at DESC)` | R3 com `status` | Igualdade em `client_id` e `status`, depois ordenação |
| `ix_ar_client_customer_created (client_id, customer_id, created_at DESC)` | R3 com `customerId` | Idem. Com ambos os filtros, este é usado e `status` é filtrado no resultado (`customerId` é mais seletivo) |

## 5. Criação e duplicidade (R1, R4, ADR-0002)

```
ActivationRequestService.create(cmd, clientId)
  1. tenta INSERT (repository.saveAndFlush, transação curta e própria)
       ok  → 201 Created
  2. DataIntegrityViolationException na constraint uk_activation_request_external_id
       → busca o existente por externalId (nova leitura, fora da transação que falhou)
       • clientId diferente               → 409 genérico (R4.5)
       • customerId/productCode iguais    → 200 com o existente (R4.2)
       • conteúdo diferente               → 409 "externalId já usado com outro conteúdo" (R4.3)
```

Por que não basta verificar antes de inserir: duas transações concorrentes executam o `SELECT` ao mesmo tempo, ambas não encontram nada e ambas inserem. Só o banco enxerga as duas ao mesmo tempo, então só ele pode garantir a unicidade. A verificação prévia vira, no máximo, uma otimização, e aqui nem é usada, porque o caso comum (externalId novo) já sai certo no primeiro INSERT.

O método de serviço **não** é `@Transactional` como um todo: a exceção do INSERT marca a transação como rollback-only, então a releitura precisa acontecer fora dela.

## 6. Processamento assíncrono (R5, ADR-0001)

A tabela `activation_request` funciona como a própria fila. Não existe tabela de outbox separada, porque o registro **é** o trabalho pendente.

```
ActivationWorker (@Scheduled, fixedDelay configurável, padrão 1s)
  ┌ TX 1 (curta) ────────────────────────────────────────────────┐
  │ SELECT ... WHERE status='PENDING' AND next_attempt_at <= now() │
  │ ORDER BY next_attempt_at LIMIT :batchSize                     │
  │ FOR UPDATE SKIP LOCKED                                        │
  │ → startProcessing() em cada um (status=PROCESSING, lease)     │
  └─ commit ─────────────────────────────────────────────────────┘
  para cada registro (fora de transação):
     ProviderClient.activate(...)  → ProviderResult (sealed)
  ┌ TX 2 (curta, por registro) ──────────────────────────────────┐
  │ recarrega por id e aplica o resultado:                        │
  │   Success           → complete()                              │
  │   PermanentFailure  → fail(reason)                            │
  │   TransientFailure  → attempts < max ? scheduleRetry(at)      │
  │                                       : fail("max attempts")  │
  │   CredentialsRejected → postpone(+1min), sem gastar tentativa │
  └─ commit (@Version protege contra escrita concorrente) ───────┘

Reconciliação (ActivationWorker.releaseExpiredClaims, @Scheduled, padrão 30s)
  PROCESSING com processing_started_at < now() - leaseTimeout (padrão 2min)
  → mesma regra de uma falha temporária (conta como tentativa):
    attempts < max ? scheduleRetry(backoff) : fail("max attempts")
```

- **Garantia de entrega (R5.1):** o registro é gravado como `PENDING` no commit do `POST`. Se a aplicação cair antes de chamar o provedor, o worker pega o registro no próximo ciclo. Se cair durante a chamada, a reconciliação devolve o registro para a fila. A entrega é *at-least-once*. A duplicidade no provedor é evitada pela `Idempotency-Key`.
- **Várias instâncias (R5.8):** `SKIP LOCKED` faz cada instância pegar um lote diferente sem bloqueio entre elas.
- **Resposta de 2xx ou 4xx perdida depois de um timeout:** o retry reenvia a mesma `Idempotency-Key`, e um provedor idempotente devolve o resultado original.
- **Resultado atrasado:** cada claim leva o número da tentativa (`attempts` no momento do claim). O resultado só é gravado se o registro continua `PROCESSING` **na mesma tentativa**. Se o lease venceu e o registro foi liberado ou reclamado de novo, a resposta tardia é descartada com um log. O `@Version` cobre a escrita concorrente que ainda escape dessa checagem.
- **Lease vs timeouts:** `lease-timeout` (2min) precisa ser maior que `batch-size × (connect-timeout + read-timeout)` (10 × 7s), senão um lote lento seria reclamado durante a própria execução.
- **Desligamento:** o pool de agendamento espera até 30s pela tarefa em andamento (`await-termination`), para que uma chamada já feita tenha o resultado gravado. O que não terminar a tempo é recuperado pela reconciliação.
- **Testes:** o worker fica desligado (`activation.processing.enabled=false`) nos testes de integração e é chamado diretamente, um ciclo por chamada. Assim os cenários são determinísticos. Um único teste (`ScheduledProcessingIT`) liga o agendador e valida o fluxo de ponta a ponta com Awaitility.

### Classificação do retorno (R5.3)

```java
sealed interface ProviderResult {
  record Success() implements ProviderResult {}
  record PermanentFailure(String reason) implements ProviderResult {}
  record TransientFailure(String reason, Optional<Duration> retryAfter) implements ProviderResult {}
  record CredentialsRejected(String reason) implements ProviderResult {} // 401/403: postpone(), sem contar tentativa
}
```

A classificação HTTP → `ProviderResult` fica isolada no `provider/` e é testada de forma unitária com cada status code.

### Política de retry (R5.6)

| Parâmetro | Padrão | Propriedade |
|---|---|---|
| Máximo de tentativas | 5 | `activation.retry.max-attempts` |
| Atraso inicial | 2s | `activation.retry.initial-delay` |
| Multiplicador | 2 | `activation.retry.multiplier` |
| Atraso máximo | 5min | `activation.retry.max-delay` |
| Jitter | *full jitter*: `random(0, delay)` | — |

`Retry-After` (em segundos ou como data HTTP) substitui o atraso calculado, limitado ao atraso máximo.

### Cliente HTTP (R5.4, R5.5)

- `RestClient` com `JdkClientHttpRequestFactory`: `connect-timeout` padrão 2s e `read-timeout` padrão 5s, configuráveis em `provider.*`.
- Headers enviados: `Idempotency-Key: {id}`, `X-Correlation-Id: {correlationId}` e `Authorization: Bearer {provider.api-key}` (credencial lida de variável de ambiente e nunca registrada em log).
- Corpo: `{ "requestId", "customerId", "productCode" }`.

## 7. Erros (R7)

`@RestControllerAdvice` estendendo `ResponseEntityExceptionHandler`. `type` = `https://api.luigicuri.dev/problems/{slug}`.

| Situação | Status | slug |
|---|---|---|
| Bean Validation / parâmetro inválido | 400 | `validation-error` |
| JSON malformado | 400 | `malformed-request` |
| Não encontrado (inclusive de outro dono) | 404 | `not-found` |
| Duplicidade com conteúdo diferente / de outro dono | 409 | `duplicate-request` |
| Corpo acima de 16 KB | 413 | `payload-too-large` |
| Sem token / token inválido | 401 | `unauthorized` (via `AuthenticationEntryPoint`) |
| Escopo insuficiente | 403 | `forbidden` (via `AccessDeniedHandler`) |
| Qualquer outra exceção | 500 | `internal-error`, mensagem genérica, stack trace só no log |

O `traceId` vem do MDC (preenchido pelo filtro de correlação) e também é devolvido no header `X-Correlation-Id`.

## 8. Segurança (R8, ADR-0004)

- **Fluxo:** *Client Credentials* (OAuth 2.0). A comunicação é entre sistemas, sem usuário humano.
- **Validação:** `issuer-uri` → descoberta do JWKS (as chaves são buscadas e guardadas em cache, o que suporta rotação). Valida assinatura, `iss`, `exp`/`nbf` e `aud = activation-service`, esta última via a propriedade `spring.security.oauth2.resourceserver.jwt.audiences`, sem validador customizado. A descoberta é preguiçosa: a aplicação sobe mesmo que o servidor de autorização ainda não esteja acessível.
- **Firewall:** requisições rejeitadas pelo `StrictHttpFirewall` (caracteres de controle em headers, URLs malformadas) respondem `400` em ProblemDetail, e não `500`.
- **Tamanho do corpo:** um filtro recusa `Content-Length` acima de 16 KB antes da leitura. Corpos *chunked* são cortados durante a leitura. Os dois casos respondem `413`.
- **Autorização:** `POST` exige `SCOPE_activation:write`. `GET` exige `SCOPE_activation:read`.
- **Dono:** claim `client_id` (RFC 9068), com fallback para `azp` e depois `sub`. Gravado em `client_id` na criação e aplicado como filtro em toda consulta (`findByIdAndClientId`).
- **Ambiente local:** o `docker-compose` inclui `mock-oauth2-server` (navikt), que emite tokens reais via client credentials. Nos testes, o `jwt()` do `spring-security-test` dispensa servidor.
- **Credencial do provedor:** variável de ambiente injetada a partir de um cofre de segredos (Azure Key Vault / AWS Secrets Manager) em produção. A rotação é descrita no README.

## 9. Observabilidade (R9)

- Actuator: `health` (com grupos `liveness`/`readiness`) e `prometheus` na porta de gestão `8081`.
- Métricas customizadas:
  - `activation.provider.calls` (timer, tag `outcome=success|permanent_failure|transient_failure|credentials_rejected`)
  - `activation.requests.transitions` (counter, tag `to=<status>`)
  - `activation.requests.active` (gauge, tag `status=PENDING|PROCESSING`): `count` a cada coleta, barato pelos índices parciais; base dos alertas de fila parada
- `CorrelationIdFilter`: lê ou gera o `X-Correlation-Id` e o coloca no MDC como `traceId`. O worker restaura o MDC a partir de `correlation_id` antes de chamar o provedor.
- Logs: texto em desenvolvimento e JSON (`logging.structured.format.console=ecs`) no profile `docker`.

## 10. Estratégia de testes

| Nível | O quê | Ferramentas |
|---|---|---|
| Unitário (sem Spring) | Transições de estado, política de retry/backoff, classificação do `ProviderResult`, parsing de `Retry-After` | JUnit, AssertJ |
| Unitário com mocks | Serviço de criação: os três ramos de duplicidade | Mockito |
| Slice web | Validação, formato do ProblemDetail, 401/403, 413 | `@WebMvcTest`, MockMvc, `jwt()` |
| Integração | Fluxo completo criação → `COMPLETED`; 400 → `FAILED`; 500 → retry → sucesso; timeout; 429 com `Retry-After`; limite de tentativas; reconciliação; duplicidade concorrente (N threads); isolamento entre donos | `@SpringBootTest`, Testcontainers (PostgreSQL), WireMock, Awaitility |
| Arquitetura | Regras de dependência entre pacotes | ArchUnit |

Os testes de integração usam o banco real porque os comportamentos críticos (constraint única, `SKIP LOCKED`, `TIMESTAMPTZ`) são do PostgreSQL. O H2 daria falsa confiança.
