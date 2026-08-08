/* WIN-46 (BUG-5, main 存量) 浏览器 E2E 回归：登录页 #app-boot-loader 遮罩不得拦截鼠标点击
 *
 * 背景（QA-WIN43-002）：登录页被 #app-boot-loader 全屏遮罩（z-[99999]）永久覆盖，
 * 鼠标无法点击登录按钮（Playwright 实测 elementFromPoint 命中遮罩，仅键盘 Tab+Enter
 * 可绕过）。根因：遮罩唯一移除点在 useWideMode（仅登录后挂载），WIN-30 AuthGate 门禁
 * 后登录页不再挂载 WorkspaceShell → 遮罩永不移除。
 *
 * 本脚本自包含：内置一个极简静态服务器（托管 frontend/out 静态导出 + 桩 /api/auth/me
 * 返回 401 → AuthGate 渲染 LoginPage；桩 /api/auth/login 记录请求并返回 401 →
 * 登录表单收到点击后显示错误，证明鼠标点击真正到达交互元素）。
 *
 * 运行前置：
 *   1) cd frontend && npm run build   （生成 frontend/out 静态导出）
 *   2) NODE_PATH=<playwright 所在 node_modules> node frontend/e2e/boot-loader.e2e.mjs
 *      （本项目机器上：NODE_PATH=C:/Users/Administrator/qa_tmp/node_modules）
 *
 * 断言（全部通过才算修复有效）：
 *   A. 登录页加载后 #app-boot-loader 已从 DOM 移除（不再永久覆盖）
 *   B. elementFromPoint(登录按钮中心) 命中按钮自身（而非遮罩）—— pointer events 不被拦截
 *   C. 真实鼠标点击（page.mouse.click 坐标点击）登录按钮 → 表单响应（出现"请输入用户名"）
 *   D. 填写后再次鼠标点击登录 → 登录请求真实发出（桩服务器收到 POST /api/auth/login）
 */
import { createServer } from 'node:http';
import { existsSync, statSync, mkdirSync, createReadStream } from 'node:fs';
import { dirname, join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

// playwright 非仓库依赖（QA 环境独立安装）；运行时通过 NODE_PATH 提供，
// ESM import 不读 NODE_PATH，故用 createRequire 走 CommonJS 解析。
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(HERE, '..', 'out');
const PORT = 4173;
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = join(HERE, '..', '..', 'docs', 'qa', 'win46');

let loginPosts = 0;
let meCalls = 0;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.webp': 'image/webp',
  '.txt': 'text/plain; charset=utf-8',
  '.woff2': 'font/woff2',
  '.xml': 'application/xml',
  '.webmanifest': 'application/manifest+json',
};

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  const p = url.pathname;

  if (p === '/api/auth/me') {
    meCalls++;
    return sendJson(res, 401, { message: '未登录' });
  }
  if (p === '/api/auth/login' && req.method === 'POST') {
    loginPosts++;
    return sendJson(res, 401, { message: '用户名或密码错误' });
  }
  if (p.startsWith('/api/')) {
    return sendJson(res, 404, { message: 'not found' });
  }

  // 静态导出：目录请求 → index.html；文件请求 → 直接命中；否则回退 index.html
  let rel = decodeURIComponent(p).replace(/^\/+/, '');
  let file = join(OUT_DIR, rel || 'index.html');
  if (!existsSync(file) || statSync(file).isDirectory()) {
    file = join(OUT_DIR, rel, 'index.html');
  }
  if (!existsSync(file) || statSync(file).isDirectory()) {
    file = join(OUT_DIR, 'index.html');
  }
  const ext = extname(file).toLowerCase();
  res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
  createReadStream(file).pipe(res);
});

function check(name, ok, extra = '') {
  if (ok) console.log(`  PASS | ${name}${extra ? ' — ' + extra : ''}`);
  else console.log(`  FAIL | ${name}${extra ? ' — ' + extra : ''}`);
  return ok;
}

const results = [];
let exitCode = 0;
let browser;

try {
  await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
  mkdirSync(SHOTS, { recursive: true });

  browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

  console.log(`== WIN-46 BUG-5 登录页遮罩回归 E2E（BASE=${BASE}）==`);
  await page.goto(BASE + '/', { waitUntil: 'load', timeout: 30000 });
  await page.waitForSelector('button:has-text("登录")', { timeout: 20000 });
  await page.waitForTimeout(1200); // 等待 AuthGate 水合完成（/api/auth/me 401 → LoginPage）

  // A. 遮罩已从 DOM 移除
  const loaderGone = await page.evaluate(() => !document.getElementById('app-boot-loader'));
  results.push(['A. #app-boot-loader 已从 DOM 移除（不再永久覆盖）', check('A. #app-boot-loader 已从 DOM 移除', loaderGone)]);

  // B. elementFromPoint 命中登录按钮自身（pointer events 不被拦截）
  const hit = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('登录'));
    const r = btn.getBoundingClientRect();
    const el = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return { isButton: btn.contains(el), hitId: el ? el.id : null, hitClass: el ? el.className : '' };
  });
  results.push([
    'B. elementFromPoint 命中登录按钮',
    check('B. elementFromPoint 命中登录按钮', hit.isButton, `hit=${hit.hitId || hit.hitClass.slice(0, 40) || 'button'}`),
  ]);

  // C. 真实鼠标点击（坐标级，绕过 Playwright 可交互性检查）→ 表单响应"请输入用户名"
  const btnBox = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('登录'));
    const r = btn.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  await page.mouse.click(btnBox.x, btnBox.y);
  let formResponded = false;
  try {
    await page.waitForSelector('text=请输入用户名', { timeout: 5000 });
    formResponded = true;
  } catch { /* no response */ }
  results.push([
    'C. 鼠标点击登录按钮触发表单校验',
    check('C. 鼠标点击登录按钮触发表单校验', formResponded),
  ]);

  // D. 填写凭据后鼠标点击 → 登录请求真实发出
  await page.getByPlaceholder('2-32 位字母、数字、下划线、短横线或中文').fill('win46_e2e_user');
  await page.getByPlaceholder('请输入密码').fill('secret123');
  const btnBox2 = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('登录'));
    const r = btn.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  await page.mouse.click(btnBox2.x, btnBox2.y);
  await page.waitForTimeout(1500);
  results.push([
    'D. 登录请求真实发出（桩收到 POST /api/auth/login）',
    check('D. 登录请求真实发出（桩收到 POST /api/auth/login）', loginPosts >= 1, `posts=${loginPosts}, /api/auth/me=${meCalls}`),
  ]);

  await page.screenshot({ path: join(SHOTS, 'login-page-fixed.png') });
} catch (err) {
  console.log('  ERROR | ' + err.message);
  exitCode = 1;
} finally {
  const failed = results.filter(([, ok]) => !ok).length;
  console.log(`\n== 结果：${results.length - failed}/${results.length} 通过 ==`);
  if (failed > 0 || results.length < 4) exitCode = 1;
  if (browser) await browser.close();
  await new Promise((r) => server.close(r));
  process.exit(exitCode);
}
