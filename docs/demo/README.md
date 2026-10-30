# Demonstração em vídeo

[`demonstracao-activation-service.webm`](https://github.com/Luigicuri/luigicuri-activation-service/raw/main/docs/demo/demonstracao-activation-service.webm) (2 min, 4 MB; o link baixa o arquivo, que abre em qualquer navegador) mostra cinco cenários executados pelo Swagger UI, com a stack do `docker compose`:

1. Login OAuth 2.0, criação (`201`, `PENDING`) e consulta (`COMPLETED` cerca de 1 s depois)
2. Duplicidade: reenvio idêntico (`200`, mesmo `id`) e mesmo `externalId` com outro produto (`409`)
3. Dados inválidos (`400` com os erros por campo)
4. Produto recusado pelo provedor (`201` na criação, `FAILED` com o motivo na consulta)
5. Permissões: cliente só leitura recebe `403` ao criar e `404` ao ler a solicitação de outro sistema

No fim, o trace do cenário 1 no Jaeger: o `POST`, o processamento assíncrono e a chamada ao provedor num único trace.

## Como foi gravado

A gravação não é manual: o script [`record.js`](record.js) usa o [Playwright](https://playwright.dev) para operar o Swagger UI como um usuário faria (clicar, preencher, executar) e grava a tela. As legendas são inseridas pelo próprio script. As respostas exibidas são as reais, da aplicação rodando.

Para gravar de novo, com a stack no ar (`docker compose up -d --build`) e o Edge instalado:

```bash
npm install playwright@1.48.2
npx playwright install ffmpeg
node docs/demo/record.js
```

O vídeo fica em `docs/demo/video/`.
