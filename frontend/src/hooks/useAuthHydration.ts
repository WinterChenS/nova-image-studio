'use client';

import { useCallback, useEffect, useState } from 'react';
import { getMe, logout, onAuthChange, type AuthUser } from '@/lib/auth';
import { fetchCatalog } from '@/lib/catalog-api';
import { fetchSettings } from '@/lib/settings-api';
import {
  clearCatalogCache,
  ensureDefaults,
  setCatalogCache,
  setRegistryCache,
  type DefaultModels,
} from '@/lib/nova-models';
import { syncDynamicModelExports } from '@/lib/gemini-config';

/**
 * WIN-25 (T18/T22) — 登录态 + 数据层水合：
 *
 * - 挂载时校验 token（/api/auth/me），订阅 nova-auth-changed（登录/登出）；
 * - 登录后从 API 拉取**全局模型目录**（GET /api/nova/models → catalogCache，
 *   T18：模型下拉/默认模型走目录，available 禁用）与用户默认模型设置
 *   （settings['registry.defaults']）；登出则清缓存回退本地。
 *
 * eslint 注：react-hooks/set-state-in-effect 对本文件的水合 setState 是误报
 * （setState 都发生在异步回调内，非同步 effect 体）；仓库内既有同类模式保留。
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
      clearCatalogCache();
      syncDynamicModelExports();
      // eslint-disable-next-line react-hooks/set-state-in-effect -- hydration completion
      setHydrated(true);
      return;
    }
    Promise.all([
      fetchCatalog().catch(() => []),
      fetchSettings().catch((): Record<string, unknown> => ({})),
    ])
      .then(([catalog, settings]) => {
        if (cancelled) return;
        // T18: 目录为唯一模型源；defaults 沿用用户设置（无效则回退第一个可用）
        setCatalogCache(catalog);
        const defaults = settings['registry.defaults'] as Partial<DefaultModels> | undefined;
        setRegistryCache({
          imageModels: [],
          textModels: [],
          defaults: ensureDefaults(defaults || {}, [], []),
        });
        syncDynamicModelExports();
        window.dispatchEvent(new Event('nova-model-registry-updated'));
      })
      .catch(() => {
        if (cancelled) return;
        clearCatalogCache();
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
