'use client';

/**
 * WIN-25 (T22, ADR-33) — SPA 根级登录门禁：挂载时校验 token（/api/auth/me），
 * 未登录渲染全页 LoginPage，登录后渲染内部应用（A17/A18）。`output: export` +
 * PWA 形态下等效路由守卫（无独立 /login 路由）。同时负责登录后的目录水合
 * （catalog → loadRegistry()，T18）。
 *
 * WIN-46 (BUG-5, main 存量)：未登录路径渲染 LoginPage 前必须移除
 * `#app-boot-loader` 启动遮罩。历史上遮罩仅由 useWideMode（登录后 WorkspaceShell）
 * 移除，登录页因此被 z-[99999] 全屏遮罩永久覆盖、鼠标无法点击登录按钮。
 */

import { useEffect } from 'react';
import { LoginPage } from '@/components/LoginPage';
import { useAuthHydration } from '@/hooks/useAuthHydration';
import { dismissBootLoader } from '@/lib/boot-loader';

export function AuthGate({ children }: { children: React.ReactNode }) {
  const { user, hydrated } = useAuthHydration();

  // 水合完成且未登录（即将渲染 LoginPage）时移除启动遮罩，登录表单不再被拦截；
  // 登录态路径不移除——宽屏闪屏保护仍由 useWideMode 在状态落定后移除（幂等）。
  useEffect(() => {
    if (hydrated && !user) {
      dismissBootLoader();
    }
  }, [hydrated, user]);

  if (!hydrated) {
    return (
      <div className="fixed inset-0 z-[99999] flex items-center justify-center bg-background">
        <svg className="h-8 w-8 animate-spin text-primary" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
        </svg>
      </div>
    );
  }

  if (!user) {
    return <LoginPage />;
  }

  return <>{children}</>;
}
