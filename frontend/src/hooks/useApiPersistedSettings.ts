'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchSetting, saveSetting } from '@/lib/settings-api';

/**
 * 服务端持久化设置 Hook（M2 T2.3）——工作台表单默认值（workbench.*）改走
 * 设置 API，不再写 localStorage。与旧 usePersistedSettings 同构：
 *
 * - 挂载时从设置 API 读取（key 缺失用 defaults），合并后置 ready；
 * - ready 前不写回，避免用默认值覆盖服务端数据；
 * - 变化后 debounce 写回 API（400ms）；失败静默（下轮重试/刷新兜底）。
 *
 * UI 偏好（theme/wide-mode/素材排序等）仍用旧的 usePersistedSettings 留本地。
 */
export function useApiPersistedSettings<T extends Record<string, unknown>>(
  key: string,
  defaults: T,
  debounceMs = 400,
): [T, (update: Partial<T>) => void] {
  const [values, setValues] = useState<T>(defaults);
  const [ready, setReady] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latestRef = useRef<T>(defaults);

  useEffect(() => {
    let cancelled = false;
    fetchSetting<T>(key, defaults).then((loaded) => {
      if (cancelled) return;
      const merged = { ...defaults };
      for (const k of Object.keys(defaults) as Array<keyof T>) {
        if (k in loaded && (loaded as Record<string, unknown>)[k as string] !== undefined) {
          (merged as Record<string, unknown>)[k as string] = (loaded as Record<string, unknown>)[k as string];
        }
      }
      setValues(merged);
      latestRef.current = merged;
      setReady(true);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  useEffect(() => {
    if (!ready) return;
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      saveSetting(key, latestRef.current).catch(() => {
        // 网络/401 失败静默：下次刷新或手动保存会重试
      });
    }, debounceMs);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [values, ready]);

  const setPartial = useCallback((update: Partial<T>) => {
    setValues((prev) => {
      const next = { ...prev, ...update };
      latestRef.current = next;
      return next;
    });
  }, []);

  return [values, setPartial];
}
