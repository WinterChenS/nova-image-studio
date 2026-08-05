'use client';

import { useCallback, useEffect, useState } from 'react';
import { getMe, logout, onAuthChange, type AuthUser } from '@/lib/auth';
import { loadRegistryFromApi } from '@/lib/settings-api';
import { clearRegistryCache, setRegistryCache } from '@/lib/nova-models';
import { syncDynamicModelExports } from '@/lib/gemini-config';

/**
 * M2 T2.5 — 登录态 + 数据层水合：
 *
 * - 挂载时校验 token（/api/auth/me），订阅 nova-auth-changed；
 * - 登录后从 API 拉取模型注册表灌入内存缓存（loadRegistry() 同步读取方
 *   立即拿到最新数据）并刷新动态模型导出；登出则清缓存回退 localStorage
 *   （匿名只读边界 Q1）。
 *
 * eslint 注：react-hooks/set-state-in-effect 对本文件的水合 setState 是误报
 * （setState 都发生在异步回调内，非同步 effect 体）；仓库内既有同类模式
 * （WorkspaceShell/ImageGenerationWorkbench 等）同样保留。
 */
export function useAuthHydration() {
  const [user, setUser] = useState<AuthUser | null | undefined>(undefined);
  const [hydrated, setHydrated] = useState(false);

  const refreshUser = useCallback(async () => {
    const next = await getMe();
    setUser(next);
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async token check
    void refreshUser();
    const unsubscribe = onAuthChange(() => void refreshUser());
    return unsubscribe;
  }, [refreshUser]);

  useEffect(() => {
    let cancelled = false;
    if (!user) {
      clearRegistryCache();
      syncDynamicModelExports();
      // eslint-disable-next-line react-hooks/set-state-in-effect -- hydration completion
      setHydrated(true);
      return;
    }
    loadRegistryFromApi()
      .then((registry) => {
        if (cancelled) return;
        setRegistryCache(registry);
        syncDynamicModelExports();
        window.dispatchEvent(new Event('nova-model-registry-updated'));
      })
      .catch(() => {
        if (cancelled) return;
        clearRegistryCache();
        syncDynamicModelExports();
      })
      .finally(() => {
        if (!cancelled) {
          setHydrated(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [user]);

  const handleLogout = useCallback(() => {
    logout();
    setUser(null);
  }, []);

  return { user, hydrated, handleLogout };
}
