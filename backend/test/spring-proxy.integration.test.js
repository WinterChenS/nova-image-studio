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
        const body = Buffer.concat(chunks).toString('utf8');
        requests.push({ method: req.method, url: req.url, headers: req.headers, body });

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
