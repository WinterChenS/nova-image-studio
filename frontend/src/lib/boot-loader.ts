'use client';

/**
 * WIN-46 (BUG-5, main 存量) — `#app-boot-loader` 启动遮罩的移除工具。
 *
 * layout.tsx 的 SSR 输出会在 <body> 下渲染一个全屏（fixed inset-0 z-[99999]）
 * 启动遮罩，用于在客户端水合完成、真实布局就绪前覆盖首屏，避免闪现。
 * 该遮罩一旦水合完成必须被移除，否则会永久拦截整页的 pointer events。
 *
 * 历史缺陷：移除逻辑原本内联在 useWideMode 中，而 useWideMode 仅由登录后的
 * WorkspaceShell 挂载 —— WIN-30 AuthGate 根级登录门禁后，未登录用户只会渲染
 * LoginPage，遮罩永不移除，鼠标无法点击登录按钮。
 *
 * 移除时机（两处，均为幂等）：
 * 1. AuthGate 水合完成且未登录（渲染 LoginPage 前）——登录路径；
 * 2. useWideMode 宽屏状态落定后 —— 登录态工作台路径（保留宽屏闪屏保护）。
 */
export function dismissBootLoader(): void {
  if (typeof document === 'undefined') return;
  const el = document.getElementById('app-boot-loader');
  if (el) el.remove();
}
