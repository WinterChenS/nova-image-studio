'use client';

/**
 * WIN-22 (ADR-19) — 外层视图容器：工作台 ↔ 管理控制台 切换。
 * 单页 SPA 状态切换（静态导出友好，不新增 Next 路由页）。
 */

import { createContext, useCallback, useContext, useState } from 'react';
import { CurrentProjectProvider } from '@/hooks/useCurrentProject';

export type AppView = 'workbench' | 'console';

interface AppShellContextValue {
  view: AppView;
  enterConsole: () => void;
  exitConsole: () => void;
}

const AppShellContext = createContext<AppShellContextValue | null>(null);

export function useAppShell(): AppShellContextValue {
  const ctx = useContext(AppShellContext);
  if (!ctx) throw new Error('useAppShell 必须在 AppShell 内使用');
  return ctx;
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const [view, setView] = useState<AppView>('workbench');

  const enterConsole = useCallback(() => setView('console'), []);
  const exitConsole = useCallback(() => setView('workbench'), []);

  return (
    <AppShellContext.Provider value={{ view, enterConsole, exitConsole }}>
      <CurrentProjectProvider>{children}</CurrentProjectProvider>
    </AppShellContext.Provider>
  );
}
