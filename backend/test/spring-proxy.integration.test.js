const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const BACKEND_DIR = path.resolve(__dirname, '..');

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  server.closeAllConnections?.();
  return new Promise(resolve => server.close(resolve));
}

async function waitFor(predicate, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await predicate();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw lastError || new Error('Timed out waiting for condition');
}

async function stopBackend(child) {
  if (child.exitCode !== null) return;
  child.kill('SIGTERM');
  const stopped = await Promise.race([
    new Promise(resolve => child.once('exit', () => resolve(true))),
    new Promise(resolve => setTimeout(() => resolve(false), 3000)),
  ]);
  if (!stopped && child.exitCode === null) {
    child.kill('SIGKILL');
    await new Promise(resolve => child.once('exit', resolve));
  }
}

/** 启动一个 mock Spring 上游（模拟 AuthController + SettingsController 行为），返回 { port, server }。 */
function startMockSpring(requests) {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const chunks = [];
      req.on('data', c => chunks.push(c));
      req.on('end', () => {
        const rawBody = Buffer.concat(chunks);
        const body = rawBody.toString('utf8');
        requests.push({ method: req.method, url: req.url, headers: req.headers, body, rawBody });

        const url = new URL(req.url, 'http://mock');
        const pathname = url.pathname.replace(/\/+$/, '');

        if (req.method === 'POST' && pathname === '/api/auth/register') {
          res.writeHead(201, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ id: 'u-1', username: 'tester', role: 'admin' }));
          return;
        }
        if (req.method === 'POST' && pathname === '/api/auth/login') {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ token: 'mock-token', user: { id: 'u-1', username: 'tester', role: 'admin' } }));
          return;
        }
        if (req.method === 'GET' && pathname === '/api/auth/me') {
          if (req.headers.authorization === 'Bearer mock-token') {
            res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
            res.end(JSON.stringify({ id: 'u-1', username: 'tester', role: 'admin' }));
          } else {
            res.writeHead(401, { 'Content-Type': 'application/json; charset=utf-8' });
            res.end(JSON.stringify({ error: '请先登录', code: 'UNAUTHORIZED' }));
          }
          return;
        }
        if (req.method === 'GET' && pathname === '/api/nova/settings') {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ 'registry.defaults': {} }));
          return;
        }
        // ---- WIN-22 新前缀（mock Spring 侧实现，仅验证代理透传） ----
        if (req.method === 'GET' && pathname === '/api/nova/projects') {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify([{ id: 'p1', name: '默认项目' }]));
          return;
        }
        if (req.method === 'GET' && pathname === '/api/nova/storage/health') {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ mode: 'disk', minioConfigured: false, bucket: null, bucketExists: false }));
          return;
        }
        if (req.method === 'GET' && pathname === '/api/nova/admin/users') {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify([{ id: 'u1', username: 'admin', role: 'admin', status: 'active' }]));
          return;
        }
        if (req.method === 'GET' && pathname === '/api/nova/tasks') {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ items: [], total: 0, page: 1, size: 20 }));
          return;
        }
        if (req.method === 'PATCH' && /^\/api\/nova\/tasks\/[^/]+\/project$/.test(pathname)) {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ ok: true }));
          return;
        }
        if (req.method === 'POST' && pathname === '/api/nova/assets') {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ id: 'a1', ok: true }));
          return;
        }
        res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'Not Found' }));
      });
    });
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve({ port: server.address().port, server }));
  });
}

async function startNodeBackend(env) {
  const portProbe = http.createServer();
  const backendPort = await listen(portProbe);
  await close(portProbe);

  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nova-spring-proxy-'));
  const child = spawn(process.execPath, ['server.js'], {
    cwd: BACKEND_DIR,
    env: {
      ...process.env,
      NODE_ENV: 'production',
      HOSTNAME: '127.0.0.1',
      PORT: String(backendPort),
      NOVA_TASK_DB: path.join(tempDir, 'tasks.sqlite'),
      NOVA_IMAGE_DIR: path.join(tempDir, 'images'),
      ...env,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let backendOutput = '';
  child.stdout.on('data', chunk => { backendOutput += chunk; });
  child.stderr.on('data', chunk => { backendOutput += chunk; });

  const backendUrl = `http://127.0.0.1:${backendPort}`;
  await waitFor(async () => {
    if (child.exitCode !== null) throw new Error(`Backend exited early:\n${backendOutput}`);
    const response = await fetch(`${backendUrl}/api/nova/queue-status`);
    return response.ok;
  });
  return { child, backendUrl, tempDir, getOutput: () => backendOutput };
}

test('WIN-21: /api/auth/*（register/login/me）经 node(3000) 代理到 Spring', async t => {
  const requests = [];
  const { port: springPort, server: mockServer } = await startMockSpring(requests);
  const { child, backendUrl, tempDir, getOutput } = await startNodeBackend({
    NOVA_SPRING_API_TARGET: `http://127.0.0.1:${springPort}`,
  });
  t.after(async () => {
    await stopBackend(child);
    mockServer.closeAllConnections?.();
    await new Promise(resolve => mockServer.close(resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  // 1. register → 201（透传上游状态码）
  const registerResponse = await fetch(`${backendUrl}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'tester', password: 'secret' }),
  });
  assert.equal(registerResponse.status, 201, getOutput());
  const registered = await registerResponse.json();
  assert.equal(registered.username, 'tester');

  // 2. login → {token, user}
  const loginResponse = await fetch(`${backendUrl}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'tester', password: 'secret' }),
  });
  assert.equal(loginResponse.status, 200, getOutput());
  const login = await loginResponse.json();
  assert.equal(login.token, 'mock-token');
  assert.equal(login.user.username, 'tester');

  // 3. 尾斜杠形式 /api/auth/login/ 不再 404，且转发时去掉尾斜杠
  const loginSlashResponse = await fetch(`${backendUrl}/api/auth/login/`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'tester', password: 'secret' }),
  });
  assert.equal(loginSlashResponse.status, 200, getOutput());
  const forwardedLogin = requests.find(r => r.method === 'POST' && /^\/api\/auth\/login\/?$/.test(r.url));
  assert.ok(forwardedLogin, `未找到转发到上游的 /api/auth/login 请求: ${JSON.stringify(requests)}`);
  assert.equal(forwardedLogin.url, '/api/auth/login');

  // 4. me：带 Bearer → 200 当前用户；无 token → 401（透传上游状态码）
  const meResponse = await fetch(`${backendUrl}/api/auth/me`, {
    headers: { Authorization: 'Bearer mock-token' },
  });
  assert.equal(meResponse.status, 200, getOutput());
  const me = await meResponse.json();
  assert.equal(me.username, 'tester');

  const meUnauthorized = await fetch(`${backendUrl}/api/auth/me`);
  assert.equal(meUnauthorized.status, 401, getOutput());
  const unauthorized = await meUnauthorized.json();
  assert.equal(unauthorized.code, 'UNAUTHORIZED');

  // 5. 相邻风险：/api/nova/settings 同样代理到 Spring
  const settingsResponse = await fetch(`${backendUrl}/api/nova/settings`, {
    headers: { Authorization: 'Bearer mock-token' },
  });
  assert.equal(settingsResponse.status, 200, getOutput());
  const settings = await settingsResponse.json();
  assert.deepEqual(settings, { 'registry.defaults': {} });

  // 6. node 自有路由不受影响（/api/nova/queue-status 仍由 node 处理）
  const queueResponse = await fetch(`${backendUrl}/api/nova/queue-status`);
  assert.equal(queueResponse.status, 200, getOutput());
  const forwardedToSpring = requests.some(r => r.url.startsWith('/api/nova/queue-status'));
  assert.equal(forwardedToSpring, false, 'queue-status 不应被代理到 Spring');
});

test('WIN-21: Spring 不可达时返回 502 JSON，node 进程不崩', async t => {
  // 占用一个端口后立即关闭，制造"无监听"目标
  const deadProbe = http.createServer();
  const deadPort = await listen(deadProbe);
  await close(deadProbe);

  const { child, backendUrl, tempDir, getOutput } = await startNodeBackend({
    NOVA_SPRING_API_TARGET: `http://127.0.0.1:${deadPort}`,
  });
  t.after(async () => {
    await stopBackend(child);
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  const loginResponse = await fetch(`${backendUrl}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'tester', password: 'secret' }),
  });
  assert.equal(loginResponse.status, 502, getOutput());
  const body = await loginResponse.json();
  assert.ok(body.error, `期望 JSON {error}, 实际: ${JSON.stringify(body)}`);

  // 进程仍存活，node 自有路由正常
  const queueResponse = await fetch(`${backendUrl}/api/nova/queue-status`);
  assert.equal(queueResponse.status, 200, getOutput());
});

test('WIN-22: 新前缀 projects/assets/storage/admin 透传到 Spring', async t => {
  const requests = [];
  const { port: springPort, server: mockServer } = await startMockSpring(requests);
  const { child, backendUrl, tempDir, getOutput } = await startNodeBackend({
    NOVA_SPRING_API_TARGET: `http://127.0.0.1:${springPort}`,
  });
  t.after(async () => {
    await stopBackend(child);
    mockServer.closeAllConnections?.();
    await new Promise(resolve => mockServer.close(resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  // GET /api/nova/projects → Spring JSON
  const projects = await fetch(`${backendUrl}/api/nova/projects`, { headers: { Authorization: 'Bearer t' } });
  assert.equal(projects.status, 200, getOutput());
  assert.ok(Array.isArray(await projects.json()), 'projects 应返回数组');

  // GET /api/nova/storage/health → Spring JSON
  const health = await fetch(`${backendUrl}/api/nova/storage/health`, { headers: { Authorization: 'Bearer t' } });
  assert.equal(health.status, 200, getOutput());
  const healthBody = await health.json();
  assert.equal(healthBody.mode, 'disk');

  // GET /api/nova/admin/users → Spring JSON
  const users = await fetch(`${backendUrl}/api/nova/admin/users`, { headers: { Authorization: 'Bearer t' } });
  assert.equal(users.status, 200, getOutput());
  assert.ok(Array.isArray(await users.json()), 'users 应返回数组');

  // 确认真实转发到了 mock Spring
  assert.ok(requests.some(r => r.url.startsWith('/api/nova/projects')), 'projects 应转发');
  assert.ok(requests.some(r => r.url.startsWith('/api/nova/storage/health')), 'storage/health 应转发');
  assert.ok(requests.some(r => r.url.startsWith('/api/nova/admin/users')), 'admin/users 应转发');
});

test('WIN-22: GET /api/nova/tasks（列表）与 PATCH /project 透传 Spring；POST/单查仍走 Node', async t => {
  const requests = [];
  const { port: springPort, server: mockServer } = await startMockSpring(requests);
  const { child, backendUrl, tempDir, getOutput } = await startNodeBackend({
    NOVA_SPRING_API_TARGET: `http://127.0.0.1:${springPort}`,
  });
  t.after(async () => {
    await stopBackend(child);
    mockServer.closeAllConnections?.();
    await new Promise(resolve => mockServer.close(resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  // 1. GET 列表 → 转发 Spring（node 无列表路由，透传安全）
  const listResp = await fetch(`${backendUrl}/api/nova/tasks?projectId=p1&page=1`, {
    headers: { Authorization: 'Bearer t' },
  });
  assert.equal(listResp.status, 200, getOutput());
  assert.ok(requests.some(r => r.method === 'GET' && r.url.startsWith('/api/nova/tasks?')), 'GET 列表应转发 Spring');

  // 2. PATCH 归入 → 转发 Spring
  const patchResp = await fetch(`${backendUrl}/api/nova/tasks/t1/project`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer t' },
    body: JSON.stringify({ projectId: 'p1' }),
  });
  assert.equal(patchResp.status, 200, getOutput());
  assert.ok(requests.some(r => r.method === 'PATCH' && r.url === '/api/nova/tasks/t1/project'), 'PATCH 归入应转发 Spring');

  // 3. POST 创建仍走 Node 自有 SQLite 链路（不得转发 Spring）
  const createResp = await fetch(`${backendUrl}/api/nova/tasks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      projectId: 'p1', mode: 'text-to-image', prompt: 'x', model: 'm', parallelCount: 1,
      protocol: 'openai', apiKey: 'sk-test', baseUrl: 'https://api.openai.com/v1',
    }),
  });
  assert.equal(createResp.status, 202, getOutput());
  assert.equal(requests.some(r => r.method === 'POST' && r.url === '/api/nova/tasks'), false, 'POST 创建不得转发 Spring');
  const created = await createResp.json();
  assert.ok(created.taskId, '应返回 node 侧 taskId');

  // 4. GET 单查仍走 Node（SQLite 中能查到刚创建的任务）
  const single = await fetch(`${backendUrl}/api/nova/tasks/${created.taskId}`);
  assert.equal(single.status, 200, getOutput());
  const singleBody = await single.json();
  assert.equal(singleBody.id, created.taskId);
});

test('WIN-22: multipart 二进制请求经代理往返字节一致（Buffer 修复回归）', async t => {
  const requests = [];
  const { port: springPort, server: mockServer } = await startMockSpring(requests);
  const { child, backendUrl, tempDir, getOutput } = await startNodeBackend({
    NOVA_SPRING_API_TARGET: `http://127.0.0.1:${springPort}`,
  });
  t.after(async () => {
    await stopBackend(child);
    mockServer.closeAllConnections?.();
    await new Promise(resolve => mockServer.close(resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  // 构造一段含非 UTF-8 字节的二进制负载（模拟 PNG 图片 multipart）
  const binary = Buffer.from([
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff, 0xd8, 0xff, 0xe0,
    0x00, 0x10, 0x8a, 0x9b, 0x01, 0x02, 0x03, 0xfe, 0xfd, 0xfc, 0x00, 0x80, 0x7f,
  ]);
  const boundary = '----win22-test-boundary';
  const prefix = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.png"\r\nContent-Type: image/png\r\n\r\n`,
    'utf8',
  );
  const suffix = Buffer.from(`\r\n--${boundary}--\r\n`, 'utf8');
  const body = Buffer.concat([prefix, binary, suffix]);

  const resp = await fetch(`${backendUrl}/api/nova/assets`, {
    method: 'POST',
    headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
    body,
  });
  assert.equal(resp.status, 200, getOutput());

  // mock Spring 记录原始 Buffer，断言二进制部分逐字节一致（utf8 字符串方案会损坏）
  const forwarded = requests.find(r => r.method === 'POST' && r.url === '/api/nova/assets');
  assert.ok(forwarded, '应转发到 /api/nova/assets');
  const forwardedBuf = forwarded.rawBody;
  const start = forwardedBuf.indexOf(binary.subarray(0, 8));
  assert.ok(start >= 0, '二进制负载应原样到达上游');
  assert.ok(binary.equals(forwardedBuf.subarray(start, start + binary.length)),
    `二进制字节应逐字节一致: ${forwardedBuf.subarray(start, start + binary.length).toString('hex')}`);
});

test('WIN-22: 超过 10MB 的请求体可经代理透传（body 上限调升至 21MB）', async t => {
  const requests = [];
  const { port: springPort, server: mockServer } = await startMockSpring(requests);
  const { child, backendUrl, tempDir, getOutput } = await startNodeBackend({
    NOVA_SPRING_API_TARGET: `http://127.0.0.1:${springPort}`,
  });
  t.after(async () => {
    await stopBackend(child);
    mockServer.closeAllConnections?.();
    await new Promise(resolve => mockServer.close(resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  const bigPayload = JSON.stringify({ blob: 'x'.repeat(11 * 1024 * 1024) }); // ~11MB > 旧 10MB
  const resp = await fetch(`${backendUrl}/api/nova/assets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: bigPayload,
  });
  assert.equal(resp.status, 200, getOutput());
  const forwarded = requests.find(r => r.url === '/api/nova/assets');
  assert.ok(forwarded, '应转发到上游');
  assert.ok(forwarded.body.length > 10 * 1024 * 1024, `上游应收到 >10MB body（实际 ${forwarded.body.length}）`);
});
