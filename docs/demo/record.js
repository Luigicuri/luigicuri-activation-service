// Records an automated walkthrough of the Activation Service through Swagger UI (and the trace in Jaeger).
// Usage: see docs/demo/README.md
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const BASE = 'http://localhost:18080';
// PostgreSQL container of the compose stack, read to find the trace of scenario 1
const DB_CONTAINER = process.env.DB_CONTAINER || 'activation-service-postgres-1';
const SIZE = { width: 1280, height: 800 };
const OUT_DIR = path.join(__dirname, 'video');
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

const CAPTION_CSS = `
  #demo-caption { position: fixed; left: 0; right: 0; bottom: 0; z-index: 2147483647; padding: 14px 28px;
    background: rgba(15, 23, 32, 0.92); color: #fff; font: 600 19px/1.4 Segoe UI, Arial, sans-serif; }
  #demo-caption small { display: block; font-weight: 400; font-size: 15px; color: #c9d4e0; margin-top: 2px; }
  #demo-caption b.step { color: #ffb74d; margin-right: 8px; }`;

async function caption(page, step, title, detail = '') {
  await page.evaluate(({ css, step, title, detail }) => {
    if (!document.getElementById('demo-caption-style')) {
      const s = document.createElement('style'); s.id = 'demo-caption-style'; s.textContent = css; document.head.appendChild(s);
    }
    let el = document.getElementById('demo-caption');
    if (!el) { el = document.createElement('div'); el.id = 'demo-caption'; document.body.appendChild(el); }
    el.innerHTML = (step ? `<b class="step">${step}</b>` : '') + title + (detail ? `<small>${detail}</small>` : '');
  }, { css: CAPTION_CSS, step, title, detail });
}

async function card(page, html, ms) {
  await page.setContent(`<html><body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
    background:#0f1720;color:#fff;font-family:Segoe UI,Arial,sans-serif">${html}</body></html>`);
  await pause(ms);
}

async function center(locator) {
  await locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
  await pause(450);
}

async function click(locator) {
  await center(locator);
  await locator.evaluate((el) => { el.style.outline = '3px solid #ff9800'; el.style.outlineOffset = '2px'; });
  await pause(380);
  await locator.click();
  // The element may be gone or changed after the click (modal closed, button relabelled)
  await locator.evaluate((el) => { el.style.outline = ''; }, null, { timeout: 300 }).catch(() => {});
}

function op(page, name) {
  return page.locator(`#operations-Activation_requests-${name}`);
}

async function openOperation(page, name) {
  const block = op(page, name);
  if (!(await block.evaluate((el) => el.classList.contains('is-open')))) {
    await click(block.locator('.opblock-summary').first());
  }
  const tryOut = block.locator('.try-out__btn', { hasText: 'Try it out' });
  if (await tryOut.count()) {
    await click(tryOut);
  }
  return block;
}

async function typeBody(block, json) {
  const area = block.locator('textarea.body-param__text');
  await center(area);
  await area.fill(json);
  await pause(900);
}

async function execute(page, block) {
  const response = page.waitForResponse((r) => r.url().includes('/api/v1/requests') && r.request().method() !== 'OPTIONS');
  await click(block.locator('button.execute'));
  const r = await response;
  console.log(r.request().method(), r.url().replace(BASE, ''), r.status(), (await r.text()).slice(0, 160));
  await pause(600);
  const live = block.locator('.live-responses-table').first();
  await center(live);
  return { status: r.status(), body: await r.text() };
}

function idOf(body) {
  return JSON.parse(body).id;
}

async function authorize(page, clientId, scopes) {
  await click(page.locator('.btn.authorize').first());
  await page.waitForSelector('.modal-ux');
  const logout = page.locator('.modal-ux button', { hasText: 'Logout' });
  if (await logout.count()) {
    await click(logout.first());
    await pause(500);
  }
  const client = page.locator('#client_id_clientCredentials');
  if ((await client.inputValue()) !== clientId) {
    await client.fill(clientId);
    await pause(500);
  }
  if (scopes === 'all') {
    await click(page.locator('.modal-ux a', { hasText: 'select all' }));
  } else {
    await click(page.locator('.modal-ux a', { hasText: 'select none' }));
    for (const scope of scopes) {
      await click(page.locator(`label[for="${scope}-clientCredentials-checkbox-oauth2"]`));
    }
  }
  await pause(600);
  await click(page.locator('.modal-ux button.modal-btn.authorize'));
  await page.locator('.modal-ux button', { hasText: 'Logout' }).waitFor();
  await pause(1000);
  await click(page.locator('.modal-ux button', { hasText: 'Close' }));
  await pause(800);
}

(async () => {
  fs.rmSync(OUT_DIR, { recursive: true, force: true });
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const context = await browser.newContext({ viewport: SIZE, recordVideo: { dir: OUT_DIR, size: SIZE } });
  const page = await context.newPage();
  const stamp = Date.now().toString().slice(-6);
  const ext = (n) => `PEDIDO-${stamp}-${n}`;

  await card(page, `<div style="max-width:900px">
      <div style="font-size:40px;font-weight:700">Activation Service</div>
      <div style="font-size:22px;color:#c9d4e0;margin:10px 0 28px">Demonstração automatizada pelo Swagger UI · stack do <code>docker compose</code></div>
      <ol style="font-size:21px;line-height:1.7;color:#e6edf3">
        <li>Login OAuth 2.0, criação e ativação</li><li>Duplicidade: reenvio e conflito</li>
        <li>Dados inválidos</li><li>Produto recusado pelo provedor</li><li>Permissões entre sistemas</li>
        <li>O trace completo no Jaeger</li></ol>
      <div style="font-size:15px;color:#8b96a3;margin-top:24px">Gravada por um script (Playwright) que executa a interface como um usuário faria.</div></div>`, 6000);

  await page.goto(`${BASE}/swagger-ui.html`);
  await page.waitForSelector('.opblock');
  await caption(page, '1/5', 'Login com OAuth 2.0 (client credentials)',
    'O Swagger obtém um token real no servidor de autenticação do compose. Toda operação exige token.');
  await pause(2500);
  await authorize(page, 'orders-system', 'all');

  // 1. Create and follow the activation
  await caption(page, '1/5', 'Criar uma solicitação de ativação', 'POST /api/v1/requests com externalId, customerId e productCode.');
  let block = await openOperation(page, 'create');
  await typeBody(block, `{"externalId": "${ext(1)}", "customerId": "CLIENTE-42", "productCode": "INTERNET-500"}`);
  let res = await execute(page, block);
  const firstId = idOf(res.body);
  await caption(page, '1/5', `${res.status} Created: a solicitação nasce como PENDING`,
    'A API responde na hora, sem esperar o provedor. O header Location aponta para a nova solicitação.');
  await pause(4000);

  await caption(page, '1/5', 'Consultar o status', 'Um worker em segundo plano chamou o provedor nesse meio-tempo.');
  const get = await openOperation(page, 'get');
  const idInput = get.locator('input[placeholder="id"]');
  await center(idInput);
  await idInput.fill(firstId);
  await pause(1200);
  res = await execute(page, get);
  await caption(page, '1/5', `${res.status} OK: status ${JSON.parse(res.body).status}`,
    'A ativação foi concluída no provedor cerca de 1 s depois da criação.');
  await pause(4000);

  // 2. Duplicates
  await caption(page, '2/5', 'Duplicidade: reenviar exatamente o mesmo corpo', 'Sistemas de origem fazem retry; isso não pode criar uma segunda solicitação.');
  block = await openOperation(page, 'create');
  await pause(1200);
  res = await execute(page, block);
  await caption(page, '2/5', `${res.status} OK com o mesmo id: nada novo foi criado`, 'O retry legítimo é idempotente. Uma constraint única no banco garante isso, mesmo em paralelo.');
  await pause(4500);
  await caption(page, '2/5', 'Duplicidade: mesmo externalId com outro produto', 'Provável reaproveitamento da chave por engano no sistema de origem.');
  await typeBody(block, `{"externalId": "${ext(1)}", "customerId": "CLIENTE-42", "productCode": "TV-100"}`);
  res = await execute(page, block);
  await caption(page, '2/5', `${res.status} Conflict, explicando o conflito`, 'Erros seguem o RFC 9457 (problem+json) e trazem traceId e correlationId para achar o caso nos logs.');
  await pause(4000);

  // 3. Invalid data
  await caption(page, '3/5', 'Dados inválidos', 'externalId com espaço e customerId ausente.');
  await typeBody(block, `{"externalId": "PEDIDO 2002", "productCode": "INTERNET-500"}`);
  res = await execute(page, block);
  await caption(page, '3/5', `${res.status} Bad Request com a lista de erros por campo`, 'Nenhum detalhe interno (stack trace, SQL) chega ao consumidor.');
  await pause(4000);

  // 4. Provider rejects the product
  await caption(page, '4/5', 'Produto que o provedor recusa', 'No provedor simulado, INVALID-PRODUCT responde 400.');
  await typeBody(block, `{"externalId": "${ext(4)}", "customerId": "CLIENTE-42", "productCode": "INVALID-PRODUCT"}`);
  res = await execute(page, block);
  const rejectedId = idOf(res.body);
  await caption(page, '4/5', `${res.status} Created: a recusa acontece depois, no processamento`, 'A falha do provedor nunca vira erro HTTP na criação; ela aparece no status.');
  await pause(3500);
  await caption(page, '4/5', 'Consultar a solicitação recusada');
  await center(get);
  await idInput.fill(rejectedId);
  await pause(1200);
  res = await execute(page, get);
  const rejected = JSON.parse(res.body);
  await caption(page, '4/5', `Status ${rejected.status}, sem nova tentativa`, `failureReason: ${rejected.failureReason}. Um 400 não muda repetindo.`);
  await pause(4000);

  // 5. Permissions
  await caption(page, '5/5', 'Permissões: login como outro sistema, só com leitura', 'client_id reporting com o escopo activation:read apenas.');
  await pause(2000);
  await authorize(page, 'reporting', ['activation:read']);
  await caption(page, '5/5', 'Tentar criar sem o escopo de escrita');
  block = await openOperation(page, 'create');
  await typeBody(block, `{"externalId": "${ext(5)}", "customerId": "CLIENTE-42", "productCode": "INTERNET-500"}`);
  res = await execute(page, block);
  await caption(page, '5/5', `${res.status} Forbidden: o token não tem activation:write`);
  await pause(4000);
  await caption(page, '5/5', 'Ler a solicitação criada pelo outro sistema (cenário 1)');
  await center(get);
  await idInput.fill(firstId);
  await pause(1200);
  res = await execute(page, get);
  await caption(page, '5/5', `${res.status} Not Found, e não 403`, 'Um sistema não enxerga os dados de outro, e a resposta nem confirma que o id existe (OWASP API1, BOLA).');
  await pause(4500);

  // Trace of scenario 1 in Jaeger
  const traceParent = execSync(`docker exec ${DB_CONTAINER} psql -U activation -d activation -tAc "select trace_parent from activation_request where id = '${firstId}'"`).toString().trim();
  const traceId = traceParent.split('-')[1];
  await page.goto(`http://localhost:18686/trace/${traceId}`);
  await page.waitForSelector('text=activation process', { timeout: 30000 });
  await pause(800);
  await caption(page, 'Jaeger', 'O trace do cenário 1, de ponta a ponta',
    'O POST, o processamento assíncrono (activation process) e a chamada ao provedor aparecem num único trace.');
  await pause(7000);

  await card(page, `<div style="text-align:center">
      <div style="font-size:34px;font-weight:700">Activation Service</div>
      <div style="font-size:20px;color:#c9d4e0;margin-top:12px">github.com/Luigicuri/luigicuri-activation-service</div>
      <div style="font-size:16px;color:#8b96a3;margin-top:20px">Os mesmos cenários estão nos guias do Swagger e do Postman, em docs/guias.</div></div>`, 4000);

  const video = page.video();
  await context.close();
  await browser.close();
  console.log('video:', await video.path());
})().catch((e) => { console.error(e); process.exit(1); });
