#!/usr/bin/env node
/**
 * M1 A/B 差异测试（T1.11）— 同一前端契约下对比 Node 后端与 Spring 后端的响应。
 *
 * 用法：
 *   node ab-diff.mjs --node http://localhost:3000 --spring http://localhost:8080
 *
 * 说明：
 *   - 两个后端必须已启动（各指向同一 frontend/out 静态产物、各自的端口）。
 *   - 图片生成路径使用脚本内置的 mock 上游（不需要真实 AI Key）：
 *     通过任务请求里的 baseUrl 指向 mock server，两个后端走同一 mock。
 *   - M2 (WIN-12) Q1 用户隔离：任务创建需登录；Spring 端轮询任务状态/
 *     图片拉取/WS 订阅均携带 ab-diff 用户的 JWT（匿名只读仅限 NULL 归属
 *     遗留任务，Node 无用户体系忽略该头）——WIN-17 探针适配，保持 A/B 1:1。
 *   - 对比前对时间戳/任务ID/图片URL做归一化（两端 UUID/时间必然不同）。
 *   - 输出 PASS/FAIL 逐项结果；任何 FAIL 以非 0 退出。
 */
import { once } from 'node:events';
import http from 'node:http';

const args = process.argv.slice(2);
const nodeBase = arg('--node', 'http://localhost:3000');
const springBase = arg('--spring', 'http://localhost:8080');
const mockPort = Number(arg('--mock-port', '18099'));
// unique per run so rate-limit state from a previous run never leaks in
const runId = Date.now().toString(36);

function arg(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
}

// ---------- mock upstream ----------
const mock = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks).toString('utf8');
    console.log(`[mock] ${req.method} ${url.pathname}${url.search} bodyLen=${body.length}`);
    res.setHeader('Content-Type', 'application/json');
    if (url.pathname.endsWith('/models')) {
      res.end(JSON.stringify({ data: [{ id: 'gpt-image-1' }, { id: 'gemini-2.5-flash' }] }));
    } else if (url.pathname.endsWith('/chat/completions') || url.pathname.endsWith('/responses')
        || url.pathname.includes('generateContent')) {
      if (String(req.headers['accept'] || '').includes('text/event-stream')) {
        res.setHeader('Content-Type', 'text/event-stream');
        res.end('data: {"choices":[{"delta":{"content":"Hel"}}]}\n\ndata: {"choices":[{"delta":{"content":"lo"}}]}\n\ndata: [DONE]\n\n');
      } else {
        res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: 'hello from mock' } }] }));
      }
    } else {
      // image generation / edits
      const b64 = Buffer.from('fake-image-bytes-0123456789').toString('base64');
      res.end(JSON.stringify({ created: 1, data: [{ b64_json: b64 }] }));
    }
  });
});

// ---------- helpers ----------
function normalize(text) {
  return String(text)
    .replace(/"createdAt":"[^"]*"/g, '"createdAt":"<ts>"')
    .replace(/"completedAt":"[^"]*"/g, '"completedAt":"<ts>"')
    .replace(/"expiresAt":"[^"]*"/g, '"expiresAt":"<ts>"')
    .replace(/\/api\/nova\/images\/[a-zA-Z0-9-]+\//g, '/api/nova/images/<TASK>/')
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '<UUID>')
    .replace(/"pendingCount":\d+/g, '"pendingCount":<N>')
    .replace(/"processingCount":\d+/g, '"processingCount":<N>')
    .replace(/"queuedCount":\d+/g, '"queuedCount":<N>')
    .replace(/"remainingQueueSlots":\d+/g, '"remainingQueueSlots":<N>')
    .replace(/"displayConcurrency":\d+/g, '"displayConcurrency":<N>')
    .replace(/"displayQueued":\d+/g, '"displayQueued":<N>')
    .replace(/"created":\d+/g, '"created":<N>')
    .replace(/"retryAfter":\d+/g, '"retryAfter":<N>');
}

async function req(base, method, path, body, headers = {}) {
  const resp = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await resp.text();
  return { status: resp.status, headers: resp.headers, text };
}

// M2 (WIN-12): task creation requires login (Q1). Register a throwaway user
// against the Spring backend and attach its JWT on Spring requests; the Node
// backend ignores the header, keeping the A/B comparison 1:1.
const springAuth = { token: null };
let springAuthReady = null;
async function ensureSpringAuth() {
  if (springAuthReady) return springAuthReady;
  springAuthReady = (async () => {
    const username = 'abdiff_' + runId;
    try {
      await req(springBase, 'POST', '/api/auth/register', { username, password: 'ab-diff-secret' });
    } catch {
      // username may already exist from an earlier run
    }
    const login = await req(springBase, 'POST', '/api/auth/login', { username, password: 'ab-diff-secret' });
    if (login.status !== 200) {
      throw new Error('A/B 前置：Spring 登录失败 status=' + login.status);
    }
    springAuth.token = JSON.parse(login.text).token;
  })();
  return springAuthReady;
}

// WIN-28 (ADR-32): /api/nova/proxy 已加入 SPRING_PROXY_PREFIXES —— 双栈下
// proxy/text 与 proxy/models 均由 Spring 处理（调度选号 + usage 采集唯一实现点）。
// A/B 校验因此从「Node 自有 handler vs Spring」变为「Node 透传 → Spring vs 直连
// Spring」的一致性：有目录模型时走真实调度（两端口响应必须完全一致）；无目录模型时
// 用随机 UUID，验证两端返回同一 Spring 400（收敛后不再出现 Node 的 Missing baseUrl）。
let catalogModelId = process.env.ABDIFF_CATALOG_MODEL_ID || null;
let textCatalogModelId = process.env.ABDIFF_TEXT_CATALOG_MODEL_ID || null;
async function resolveCatalogModel() {
  if (catalogModelId) return catalogModelId;
  await ensureSpringAuth();
  const r = await req(springBase, 'GET', '/api/nova/models', null,
    clientIpFor(springBase, SPRING_IP())({}));
  if (r.status === 200) {
    try {
      const list = JSON.parse(r.text);
      const usable = list.find(m => m.available === true && m.type === 'image')
        || list.find(m => m.type === 'image') || list[0];
      if (usable) {
        catalogModelId = usable.id;
        return catalogModelId;
      }
    } catch { /* fallthrough */ }
  }
  return null;   // 无种子 → 随机 UUID 验证一致性 400
}

async function resolveTextCatalogModel() {
  if (textCatalogModelId) return textCatalogModelId;
  await ensureSpringAuth();
  const r = await req(springBase, 'GET', '/api/nova/models', null,
    clientIpFor(springBase, SPRING_IP())({}));
  if (r.status === 200) {
    try {
      const list = JSON.parse(r.text);
      const usable = list.find(m => m.available === true && m.type === 'text')
        || list.find(m => m.type === 'text');
      if (usable) {
        textCatalogModelId = usable.id;
        return textCatalogModelId;
      }
    } catch { /* fallthrough */ }
  }
  return null;   // 无种子 → 随机 UUID 验证一致性 400
}

function clientIpFor(base, ip) {
  // WIN-28 (ADR-32): /api/nova/proxy + /api/nova/usage 已透传 Spring，node(3000) 侧
  // 请求同样需要携带 JWT（两端最终都打到 Spring）。
  return (headers) => ({
    'X-Forwarded-For': ip,
    ...(springAuth.token ? { Authorization: 'Bearer ' + springAuth.token } : {}),
    ...headers,
  });
}

function jsonKeys(text) {
  try {
    return Object.keys(JSON.parse(text)).sort();
  } catch {
    return [];
  }
}

let passed = 0;
let failed = 0;
const failures = [];
let probeIndex = 0;

// Each probe runs as a distinct client IP so the per-IP rate-limit window
// from one probe never leaks into another (both backends honor X-Forwarded-For).
const NODE_IP = () => `10.0.${probeIndex}.1`;
const SPRING_IP = () => `10.0.${probeIndex}.2`;

function nextProbe() {
  probeIndex++;
  return probeIndex;
}

function check(name, cond, detail = '') {
  if (cond) {
    passed++;
    console.log(`  PASS  ${name}`);
  } else {
    failed++;
    failures.push(name);
    console.log(`  FAIL  ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

async function diff(name, fn) {
  const idx = nextProbe();
  console.log(`\n[${name}]`);
  const a = await fn(nodeBase, 'node', clientIpFor(nodeBase, NODE_IP()));
  const b = await fn(springBase, 'spring', clientIpFor(springBase, SPRING_IP()));
  return { a, b };
}

async function taskBody(mockPort, overrides = {}, base = '') {
  // WIN-28 契约分化：Node 走 legacy apiKey/baseUrl；Spring 走目录 UUID + 账号池。
  const common = {
    mode: 'text-to-image',
    prompt: 'a red apple on a table',
    outputSize: '1K',
    aspectRatio: '1:1',
    temperature: 1.0,
    gptImageQuality: 'high',
    gptImageStyle: 'auto',
    gptImageBackground: 'auto',
    parallelCount: 1,
    images: [],
    ...overrides,
  };
  if (base === springBase) {
    const modelId = await resolveCatalogModel();
    return { ...common, model: modelId || crypto.randomUUID() };
  }
  return {
    ...common,
    apiKey: 'sk-ab-diff-' + runId + '-' + probeIndex,
    baseUrl: `http://localhost:${mockPort}`,
    protocol: 'openai',
    model: 'gpt-image-1',
  };
}

async function waitCompleted(base, taskId, headers) {
  for (let i = 0; i < 100; i++) {
    const r = await req(base, 'GET', `/api/nova/tasks/${taskId}`, null, headers);
    if (r.text.includes('"completed"') || r.text.includes('"failed"')) {
      return r;
    }
    await new Promise(r => setTimeout(r, 200));
  }
  return { status: 0, text: '' };
}

// ---------- probes ----------
async function probeQueueStatus(base, _, ip) {
  const r = await req(base, 'GET', '/api/nova/queue-status', null, ip({}));
  const keys = jsonKeys(r.text);
  return {
    status: r.status,
    keys,
    hasCore: ['concurrencyLimit', 'processingCount', 'queuedCount', 'acceptingNewTasks',
      'displayConcurrency', 'displayQueued', 'maxQueueSize', 'remainingQueueSlots',
      'rateLimitWindowMs', 'rateLimitMaxRequestsPerIp', 'rateLimitMaxRequestsPerApiKey',
      'retryAfterSeconds', 'configuredConcurrency', 'pendingCount'].every(k => keys.includes(k)),
  };
}

async function probeCreatePollImage(base, _, ip) {
  const r = await req(base, 'POST', '/api/nova/tasks', await taskBody(mockPort, {}, base), ip({}));
  const taskId = JSON.parse(r.text).taskId;
  // Q1 (M2): user-owned tasks are only readable by their owner — poll with the
  // caller's headers so Spring carries the ab-diff user's JWT (Node ignores it).
  const done = await waitCompleted(base, taskId, ip({}));
  const img = done.text.includes('"completed"')
    ? await req(base, 'GET', `/api/nova/images/${taskId}/0`, null, ip({}))
    : null;
  return {
    createStatus: r.status,
    finalStatus: done.text.includes('"completed"') ? 'completed' : 'failed',
    hasImages: done.text.includes('"images"'),
    imgStatus: img?.status,
    imgCacheControl: img?.headers.get('cache-control'),
    imgContentType: img?.headers.get('content-type'),
  };
}

async function probeCreateParallel(base, _, ip) {
  const r = await req(base, 'POST', '/api/nova/tasks', await taskBody(mockPort, { parallelCount: 2 }, base), ip({}));
  const taskId = JSON.parse(r.text).taskId;
  const done = await waitCompleted(base, taskId, ip({}));
  return { createStatus: r.status, images: (done.text.match(/URL:\/api\/nova\/images/g) || []).length };
}

async function probeUnknownTask(base, _, ip) {
  const r = await req(base, 'GET', '/api/nova/tasks/does-not-exist-123', null, ip({}));
  return { status: r.status, body: normalize(r.text) };
}

async function probeAck(base, _, ip) {
  const r = await req(base, 'POST', '/api/nova/tasks/does-not-exist-123/ack', {}, ip({}));
  return { status: r.status, body: normalize(r.text) };
}

async function probeTextProxy(base, _, ip) {
  await ensureSpringAuth();
  const modelId = await resolveTextCatalogModel() || crypto.randomUUID();
  const r = await req(base, 'POST', '/api/nova/proxy/text', {
    modelId,
    stream: false,
    messages: [{ role: 'user', content: 'hi' }],
  }, ip({}));
  return { status: r.status, body: normalize(r.text) };
}

async function probeTextProxyStream(base, _, ip) {
  await ensureSpringAuth();
  const modelId = await resolveTextCatalogModel() || crypto.randomUUID();
  const r = await req(base, 'POST', '/api/nova/proxy/text', {
    modelId,
    stream: true,
    messages: [{ role: 'user', content: 'hi' }],
  }, ip({}));
  return {
    status: r.status,
    contentType: r.headers.get('content-type'),
    body: normalize(r.text).replace(/\s+/g, ' ').trim(),
  };
}

async function probeModels(base, _, ip) {
  await ensureSpringAuth();
  const modelId = await resolveCatalogModel() || crypto.randomUUID();
  const r = await req(base, 'GET', `/api/nova/proxy/models?modelId=${modelId}`, null, ip({}));
  return { status: r.status, body: normalize(r.text) };
}

async function probeUsagePrefix(base, _, ip) {
  // WIN-28: /api/nova/admin/usage 与 /api/nova/usage 前缀收敛到 Spring；
  // 非 admin 用户两端都应得到一致的 Spring 403/401（A1）。
  const r = await req(base, 'GET', '/api/nova/admin/usage', null, ip({}));
  return { status: r.status, body: normalize(r.text) };
}

async function probeGallery(base, _, ip) {
  const prompts = await req(base, 'GET', '/api/nova/prompts', null, ip({}));
  const blacklist = await req(base, 'GET', '/api/nova/blacklist', null, ip({}));
  const config = await req(base, 'GET', '/api/nova/config', null, ip({}));
  const verify = await req(base, 'POST', '/api/nova/prompt-gallery/verify', { password: 'wrong-pw' }, ip({}));
  return {
    prompts: { status: prompts.status, isArray: prompts.text.trim().startsWith('['), len: JSON.parse(prompts.text || '[]').length },
    blacklist: { status: blacklist.status, keys: jsonKeys(blacklist.text) },
    configKeys: jsonKeys(config.text),
    verify: { status: verify.status, body: normalize(verify.text) },
  };
}

async function probeStatic(base) {
  const root = await fetch(base + '/');
  const deepLink = await fetch(base + '/some/client/route');
  const missing = await fetch(base + '/_next/static/css/definitely-missing.css');
  return {
    rootStatus: root.status,
    rootType: root.headers.get('content-type'),
    deepLinkStatus: deepLink.status,
    deepLinkType: deepLink.headers.get('content-type'),
    missingStatus: missing.status,
  };
}

async function probeRateLimit(base, _, ip) {
  // exceed the per-IP window cap (default 20) with VALID payloads to force 429
  // (validation runs before the rate limit, so invalid bodies return 400 first)
  let last = null;
  for (let i = 0; i < 24; i++) {
    last = await req(base, 'POST', '/api/nova/tasks', await taskBody(mockPort, {}, base), ip({}));
    if (last.status === 429) break;
  }
  return { status: last.status, retryAfter: last.headers.get('retry-after'), body: normalize(last.text) };
}

async function probeWs(base) {
  const wsUrl = base.replace(/^http/, 'ws') + '/api/nova/ws';
  const ws = new WebSocket(wsUrl);
  const opened = await Promise.race([once(ws, 'open'), sleep(8000).then(() => 'timeout')]);
  if (opened === 'timeout') {
    try { ws.close(); } catch { /* ignore */ }
    return { hasPong: false, hasQueueStatus: false, queueKeys: [] };
  }
  const messages = [];
  ws.onmessage = e => messages.push(e.data);
  ws.send(JSON.stringify({ type: 'ping' }));
  await sleep(400);
  ws.send(JSON.stringify({ type: 'subscribeQueue' }));
  await sleep(600);
  try { ws.close(); } catch { /* ignore */ }
  return {
    hasPong: messages.some(m => m.includes('"pong"')),
    hasQueueStatus: messages.some(m => m.includes('"queueStatus"')),
    queueKeys: jsonKeys(messages.find(m => m.includes('"queueStatus"')) || ''),
  };
}

async function probeWsTaskPush(base, _, ip) {
  // T12 (ADR-27): 限流维度改为 per-user —— 用独立用户，避免前序探针（限流 429 窗口 60s）污染本探针。
  let localToken = null;
  if (base === springBase) {
    const username = 'abdiffws_' + runId + '_' + probeIndex;
    try {
      await req(base, 'POST', '/api/auth/register', { username, password: 'ab-diff-secret' });
    } catch { /* may already exist */ }
    const login = await req(base, 'POST', '/api/auth/login', { username, password: 'ab-diff-secret' });
    localToken = JSON.parse(login.text).token;
  }
  // subscribe to a task before creating it → watch queued → processing → completed
  const wsBase = base.replace(/^http/, 'ws') + '/api/nova/ws';
  // Q1 (M2): task pushes are owner-filtered — Spring's WS handshake takes
  // ?token= (browsers can't set WS headers); the Node backend ignores it.
  const token = base === springBase && localToken
    ? `?token=${encodeURIComponent(localToken)}` : '';
  const ws = new WebSocket(wsBase + token);
  const opened = await Promise.race([once(ws, 'open'), sleep(8000).then(() => 'timeout')]);
  if (opened === 'timeout') {
    try { ws.close(); } catch { /* ignore */ }
    return { statuses: [] };
  }
  const messages = [];
  ws.onmessage = e => messages.push(e.data);
  const headers = localToken ? { Authorization: 'Bearer ' + localToken } : {};
  const r = await req(base, 'POST', '/api/nova/tasks', await taskBody(mockPort, {}, base), { ...ip({}), ...headers });
  const taskId = JSON.parse(r.text).taskId;
  ws.send(JSON.stringify({ type: 'subscribeTasks', taskIds: [taskId] }));
  await sleep(2500);
  try { ws.close(); } catch { /* ignore */ }
  const statuses = messages
    .filter(m => m.includes('"type":"task"'))
    .map(m => { try { return JSON.parse(m).task.status; } catch { return '?'; } });
  return { statuses };
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

// 排队中 → processing → completed/failed (skips allowed, no regression)
function isMonotonic(statuses) {
  const rank = { '排队中': 0, queued: 0, processing: 1, completed: 2, failed: 2, expired: 2 };
  let last = -1;
  for (const s of statuses) {
    const r = rank[s];
    if (r === undefined || r < last) return false;
    last = r;
  }
  return true;
}

// ---------- run ----------
await mock.listen(mockPort);
console.log(`mock upstream on :${mockPort}`);
console.log(`node=${nodeBase} spring=${springBase}`);

// 0. M2 auth precondition (task creation requires login)
await ensureSpringAuth();
console.log('spring auth ready (ab-diff user)');

// 1. queue-status
{
  const { a, b } = await diff('1 队列状态 queue-status', probeQueueStatus);
  check('HTTP 200', a.status === 200 && b.status === 200, `${a.status} vs ${b.status}`);
  check('字段集一致', JSON.stringify(a.keys) === JSON.stringify(b.keys), `node:${a.keys} spring:${b.keys}`);
  check('核心字段齐备', a.hasCore && b.hasCore);
}

// 2. task lifecycle (t2i) + image hosting
{
  const { a, b } = await diff('2 文生图任务生命周期 + 图片托管', probeCreatePollImage);
  check('创建 202', a.createStatus === 202 && b.createStatus === 202, `${a.createStatus} vs ${b.createStatus}`);
  check('最终 completed', a.finalStatus === 'completed' && b.finalStatus === 'completed', `${a.finalStatus} vs ${b.finalStatus}`);
  check('result.images 存在', a.hasImages && b.hasImages);
  check('图片 HTTP 200', a.imgStatus === 200 && b.imgStatus === 200, `${a.imgStatus} vs ${b.imgStatus}`);
  check('Cache-Control 1h', a.imgCacheControl === 'private, max-age=3600' && b.imgCacheControl === 'private, max-age=3600',
    `${a.imgCacheControl} vs ${b.imgCacheControl}`);
  check('Content-Type image/*', (a.imgContentType || '').startsWith('image/') && (b.imgContentType || '').startsWith('image/'),
    `${a.imgContentType} vs ${b.imgContentType}`);
}

// 3. parallelCount=2
{
  const { a, b } = await diff('3 多图并行 parallelCount=2', probeCreateParallel);
  check('创建 202', a.createStatus === 202 && b.createStatus === 202);
  check('2 张图 URL', a.images === 2 && b.images === 2, `node:${a.images} spring:${b.images}`);
}

// 4. unknown task
{
  const { a, b } = await diff('4 未知任务 GET', probeUnknownTask);
  check('404', a.status === 404 && b.status === 404, `${a.status} vs ${b.status}`);
  check('expired 兜底一致', a.body === b.body, `node:${a.body} spring:${b.body}`);
}

// 5. ack
{
  const { a, b } = await diff('5 ack 续期', probeAck);
  check('200 {ok:true}', a.status === 200 && b.status === 200 && a.body === b.body, `${a.status} ${a.body} vs ${b.status} ${b.body}`);
}

// 6. text proxy non-stream (ADR-32 收敛：两端均 Spring)
{
  const { a, b } = await diff('6 文本代理非流式（modelId/账号池）', probeTextProxy);
  check('状态一致', a.status === b.status, `${a.status} vs ${b.status}`);
  check('body 一致', a.body === b.body, `node:${a.body} spring:${b.body}`);
}

// 7. text proxy stream
{
  const { a, b } = await diff('7 文本代理流式 SSE（modelId/账号池）', probeTextProxyStream);
  check('状态一致', a.status === b.status, `${a.status} vs ${b.status}`);
  check('Content-Type 一致', (a.contentType || '') === (b.contentType || ''),
    `${a.contentType} vs ${b.contentType}`);
  check('SSE body 一致', a.body === b.body, `node:[${a.body}] spring:[${b.body}]`);
}

// 8. model list proxy
{
  const { a, b } = await diff('8 模型列表代理（modelId/账号池）', probeModels);
  check('状态一致', a.status === b.status, `${a.status} vs ${b.status}`);
  check('body 一致', a.body === b.body, `node:${a.body} spring:${b.body}`);
}

// 8.5 usage prefix（WIN-28 新前缀：两端均 Spring，非 admin 403/401 一致）
{
  const { a, b } = await diff('8.5 审计接口前缀收敛 /api/nova/admin/usage', probeUsagePrefix);
  check('状态一致', a.status === b.status, `${a.status} vs ${b.status}`);
  check('body 一致', a.body === b.body, `node:${a.body} spring:${b.body}`);
}

// 9. gallery / config
{
  const { a, b } = await diff('9 提示词广场/黑名单/配置', probeGallery);
  check('prompts 200 + 数组', a.prompts.status === 200 && b.prompts.status === 200 && a.prompts.isArray && b.prompts.isArray);
  check('prompts 长度一致', a.prompts.len === b.prompts.len, `node:${a.prompts.len} spring:${b.prompts.len}`);
  check('blacklist keywords 键', JSON.stringify(a.blacklist.keys) === JSON.stringify(b.blacklist.keys));
  check('config 键一致', JSON.stringify(a.configKeys) === JSON.stringify(b.configKeys), `node:${a.configKeys} spring:${b.configKeys}`);
  check('verify 结果一致', a.verify.body === b.verify.body, `node:${a.verify.body} spring:${b.verify.body}`);
}

// 10. static hosting
{
  const { a, b } = await diff('10 静态托管 + 深链 404', probeStatic);
  check('/ 200 html', a.rootStatus === 200 && b.rootStatus === 200 && (a.rootType || '').includes('text/html')
    && (b.rootType || '').includes('text/html'), `${a.rootStatus}/${a.rootType} vs ${b.rootStatus}/${b.rootType}`);
  check('深链路由 404 一致', a.deepLinkStatus === b.deepLinkStatus, `${a.deepLinkStatus} vs ${b.deepLinkStatus}`);
  check('缺失静态资源 404', a.missingStatus === 404 && b.missingStatus === 404, `${a.missingStatus} vs ${b.missingStatus}`);
}

// 11. rate limit
{
  const { a, b } = await diff('11 限流 429', probeRateLimit);
  check('429 RATE_LIMITED / TOO_MANY_PENDING_TASKS', a.status === 429 && b.status === 429, `${a.status} vs ${b.status}`);
  check('Retry-After 头', !!a.retryAfter && !!b.retryAfter, `node:${a.retryAfter} spring:${b.retryAfter}`);
  // T12 (ADR-27): 限流维度重构为 per-user 后，两端可能先触发不同限（IP 速率 vs 每用户
  // pending 数），但均为合法 429 语义 —— 校验错误码集合而非逐字节一致。
  const okCode = t => t.includes('RATE_LIMITED') || t.includes('TOO_MANY_PENDING_TASKS');
  check('429 错误码语义一致', okCode(a.body) && okCode(b.body), `node:${a.body} spring:${b.body}`);
}

// 12. WS protocol
{
  const { a, b } = await diff('12 WebSocket 协议', probeWs);
  check('ping→pong', a.hasPong && b.hasPong);
  check('subscribeQueue→queueStatus', a.hasQueueStatus && b.hasQueueStatus);
  check('queueStatus 字段一致', JSON.stringify(a.queueKeys) === JSON.stringify(b.queueKeys),
    `node:${a.queueKeys} spring:${b.queueKeys}`);
}

// 13. WS task state push (real pipeline)
{
  const { a, b } = await diff('13 WS 任务状态推送', probeWsTaskPush);
  const seq = s => s.join('→');
  // The exact intermediate sequence is a scheduling race (Node drains the queue
  // synchronously in createTask; Spring on a virtual thread). The contract is:
  // immediate current-state push + progress to a terminal state.
  const validSeq = s => s.length > 0 && s[s.length - 1] === 'completed';
  check('收到任务推送并最终 completed', validSeq(a.statuses) && validSeq(b.statuses),
    `node:[${seq(a.statuses)}] spring:[${seq(b.statuses)}]`);
  check('无状态回退', isMonotonic(a.statuses) && isMonotonic(b.statuses),
    `node:[${seq(a.statuses)}] spring:[${seq(b.statuses)}]`);
}

await mock.close();
console.log(`\n========== 结果: ${passed} PASS / ${failed} FAIL ==========`);
if (failed > 0) {
  console.log('失败项: ' + failures.join(', '));
  process.exit(1);
}
