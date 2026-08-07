'use client';

import { AppShell, useAppShell } from '@/components/console/AppShell';
import { ToastProvider } from '@/components/console/useToast';
import { AdminConsole } from '@/components/console/AdminConsole';
import { WorkspaceShell } from '@/components/workspace/WorkspaceShell';

/**
 * WIN-22 (ADR-19) — 根视图：工作台 ↔ 管理控制台 切换（SPA 状态，静态导出友好）。
 */
function AppRoot() {
  const { view } = useAppShell();
  return view === 'console' ? <AdminConsole /> : <WorkspaceShell />;
}

export default function Home() {
  return (
    <AppShell>
      <ToastProvider>
        <AppRoot />
      </ToastProvider>
    </AppShell>
  );
}
