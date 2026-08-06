'use client';

/**
 * WIN-22 (F-3/Q11) — 当前项目上下文（工作台顶栏切换 + 生成/保存到素材定位）。
 * - 从服务端加载项目列表（首次 GET 服务端懒创建「默认项目」，R-1）；
 * - 当前项目持久化：localStorage（UI 偏好）+ `workbench.defaultProjectId`
 *   （服务端设置，F-4 白名单已有）；
 * - 生成任务与「保存到素材」携带 currentProjectId（F-4/F-13）。
 */

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import {
  fetchProjects,
  setDefaultProject,
  type ProjectDto,
} from '@/lib/projects-api';
import { getToken } from '@/lib/auth';
import { saveSetting } from '@/lib/settings-api';

const LOCAL_STORAGE_KEY = 'nova-current-project-id';

export interface CurrentProjectContextValue {
  projects: ProjectDto[];
  loading: boolean;
  error: string | null;
  currentProject: ProjectDto | null;
  setCurrentProject: (id: string) => Promise<void>;
  refresh: () => Promise<void>;
}

const CurrentProjectContext = createContext<CurrentProjectContextValue | null>(null);

function readLocal(): string | null {
  if (typeof window === 'undefined') return null;
  return window.localStorage.getItem(LOCAL_STORAGE_KEY);
}

function writeLocal(id: string): void {
  if (typeof window === 'undefined') return;
  window.localStorage.setItem(LOCAL_STORAGE_KEY, id);
}

export function CurrentProjectProvider({ children }: { children: React.ReactNode }) {
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [currentId, setCurrentId] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!getToken()) {
      setProjects([]);
      setLoading(false);
      return;
    }
    try {
      const list = await fetchProjects(false);
      setProjects(list);
      setError(null);
      // 当前项目解析：localStorage 优先 → 第一个（服务端懒创建的默认项目排前）
      const local = readLocal();
      const fallback = list[0]?.id ?? null;
      const preferred = list.find(p => p.id === local)?.id ?? fallback;
      setCurrentId(preferred);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async project list load
    void refresh();
  }, [refresh]);

  const setCurrentProject = useCallback(async (id: string) => {
    setCurrentId(id);
    writeLocal(id);
    // 服务端持久化（workbench.defaultProjectId，白名单既有）
    try {
      await saveSetting('workbench.defaultProjectId', id);
      await setDefaultProject(id).catch(() => undefined);
    } catch {
      // 非阻塞：UI 已切换，服务端下次同步
    }
  }, []);

  const value = useMemo<CurrentProjectContextValue>(() => ({
    projects,
    loading,
    error,
    currentProject: projects.find(p => p.id === currentId) ?? null,
    setCurrentProject,
    refresh,
  }), [projects, loading, error, currentId, setCurrentProject, refresh]);

  return (
    <CurrentProjectContext.Provider value={value}>
      {children}
    </CurrentProjectContext.Provider>
  );
}

export function useCurrentProject(): CurrentProjectContextValue {
  const ctx = useContext(CurrentProjectContext);
  if (!ctx) {
    throw new Error('useCurrentProject 必须在 CurrentProjectProvider 内使用');
  }
  return ctx;
}
