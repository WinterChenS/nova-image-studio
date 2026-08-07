'use client';

import { AppShell, useAppShell } from '@/components/console/AppShell';
import { ToastProvider } from '@/components/console/useToast';
import { AdminConsole } from '@/components/console/AdminConsole';
import { WorkspaceShell } from '@/components/workspace/WorkspaceShell';
import { AuthGate } from '@/components/AuthGate';

/**
 * WIN-22 (ADR-19) — 根视图：工作台 ↔ 管理控制台 切换（SPA 状态，静态导出友好）。
 * WIN-25 (T22, ADR-33) — AuthGate 根级登录门禁：未登录渲染全页登录页，
 * 登录后进入 AppShell（A17/A18）。
 */
function AppRoot() {
  const { view } = useAppShell();
  return view === 'console' ? <AdminConsole /> : <WorkspaceShell />;
}

export default function Home() {
  return (
    <AuthGate>
      <AppShell>
        <ToastProvider>
          <AppRoot />
        </ToastProvider>
      </AppShell>
    </AuthGate>
  );
}
