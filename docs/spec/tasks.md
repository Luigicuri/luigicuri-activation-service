# Tarefas

Ordem de implementação. Cada tarefa vira um ou mais commits pequenos que citam os requisitos atendidos. Ao fim de cada fase há um checkpoint de revisão.

## Fase 0 — Especificação
- [x] T0.1 `requirements.md`, `design.md`, `tasks.md` e ADRs 0001–0004

## Fase 1 — Fundação
- [x] T1.1 Projeto Maven (wrapper, Java 21, Spring Boot 3.5), `.gitignore`, `.editorconfig` — R10.1
- [x] T1.2 Migração Flyway V1 com tabela, constraints e índices — R4.1, R3.4, R10.4
- [x] T1.3 Base de teste de integração com Testcontainers (PostgreSQL) + teste de contexto — R10.1

**Checkpoint 1:** a estrutura do projeto e o esquema do banco.

## Fase 2 — Domínio e API
- [ ] T2.1 Entidade `ActivationRequest` + `ActivationStatus` + transições + teste unitário — R6
- [ ] T2.2 Repositório + `POST` (criação simples) + `GET /{id}` + DTOs (records) — R1, R2
- [ ] T2.3 Filtro de correlação + tratamento de erros com ProblemDetail — R7, R9.2
- [ ] T2.4 Duplicidade: os três ramos (B-i) + teste unitário + teste concorrente com Testcontainers — R4
- [ ] T2.5 Listagem paginada com filtros — R3

**Checkpoint 2:** domínio, API, erros e duplicidade (o núcleo da avaliação).

## Fase 3 — Integração com o provedor
- [ ] T3.1 `ProviderClient` (`RestClient`, timeouts, headers) + `ProviderResult` + testes de classificação — R5.3, R5.4, R5.5
- [ ] T3.2 `RetryPolicy` (backoff com jitter, `Retry-After`) + teste unitário — R5.6
- [ ] T3.3 `ActivationWorker` com `SKIP LOCKED` + testes de integração com WireMock (sucesso, 400, 500→sucesso, timeout, 429, limite de tentativas) — R5.1, R5.2, R5.8
- [ ] T3.4 Reconciliação de `PROCESSING` com lease vencido + teste — R5.7

**Checkpoint 3:** fluxo assíncrono ponta a ponta.

## Fase 4 — Segurança
- [ ] T4.1 Resource Server, escopos, entry point e access denied em ProblemDetail + testes de 401/403 — R8.1–R8.3
- [ ] T4.2 Dono (`client_id`) aplicado em criação, consulta, listagem e duplicidade + testes — R8.4, R4.5
- [ ] T4.3 Limite de tamanho do corpo (413) — R8.5

**Checkpoint 4:** segurança.

## Fase 5 — Operação e entrega
- [ ] T5.1 Actuator (porta de gestão, probes) + métricas Micrometer + logs JSON — R8.6, R9
- [ ] T5.2 `Dockerfile` multi-stage — R10.2
- [ ] T5.3 `docker-compose.yml` (app, Postgres, WireMock com mappings, mock-oauth2-server) + `requests.http` / script de exemplo — R10.3
- [ ] T5.4 Testes ArchUnit — R10.7
- [ ] T5.5 GitHub Actions — R10.5
- [ ] T5.6 README completo, incluindo o cenário de análise do item 10 — R10.6

**Checkpoint 5:** revisão final + simulação do code review da entrevista.
