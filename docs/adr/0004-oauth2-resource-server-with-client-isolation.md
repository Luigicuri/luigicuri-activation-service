# ADR-0004 — OAuth 2.0 Resource Server com isolamento por sistema consumidor

**Status:** Aceito

## Contexto
A API é consumida por outros sistemas, não por usuários finais. É preciso autenticar quem chama, separar quem pode criar de quem pode apenas consultar, e impedir que um sistema acesse solicitações de outro (OWASP API Top 10, API1:2023 — Broken Object Level Authorization).

## Decisão
- **Fluxo Client Credentials:** é o fluxo do OAuth 2.0 feito para comunicação entre sistemas, sem usuário humano. Cada consumidor tem seu `client_id` no servidor de autorização (Keycloak, Entra ID, Cognito...).
- **O serviço é Resource Server** e valida o JWT localmente:
  - assinatura, com chaves obtidas do JWKS publicado pelo emissor (descoberta via `issuer-uri`, cache e suporte a rotação)
  - `iss`
  - `exp` / `nbf`
  - `aud` contendo `activation-service`, para que um token emitido para outra API seja recusado
- **Escopos:** `activation:write` para `POST` e `activation:read` para `GET`.
- **Isolamento:** o `client_id` do token (claim `client_id`, com fallback para `azp` e depois `sub`) é gravado na solicitação. Toda leitura filtra por ele. O acesso a um registro de outro dono responde `404` em vez de `403`, para não confirmar que o `id` existe.
- **Respostas:** 401 e 403 no mesmo formato ProblemDetail do restante da API.

## Alternativas consideradas
| Alternativa | Por que não |
|---|---|
| API key estática por consumidor | Mais simples, mas sem expiração, sem escopos padronizados e com rotação manual |
| Introspecção do token a cada requisição (RFC 7662) | Permite revogação imediata, mas adiciona uma chamada de rede por requisição. O JWT com expiração curta é suficiente aqui |
| mTLS entre serviços | Complementar, não substituto. Faz sentido no service mesh, mas não substitui a autorização por escopo |

## Consequências
- (+) Testável sem servidor de autorização (`jwt()` do `spring-security-test`). Localmente, o `mock-oauth2-server` emite tokens reais.
- (+) A segurança é aplicada no domínio do dado, não só na borda.
- (−) Revogação só no vencimento do token. Mitigação: tokens de vida curta (5–15 min).
- **Credencial para chamar o provedor:** fica fora do código, como variável de ambiente injetada de um cofre (Azure Key Vault / AWS Secrets Manager). A rotação acontece no cofre, com duas credenciais válidas durante a janela de troca, e nunca aparece em logs.
