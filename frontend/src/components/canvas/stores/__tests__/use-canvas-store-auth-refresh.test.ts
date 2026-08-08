import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * WIN-44 BUG-1 — 画布项目列表在登录后不加载（需刷新）：
 * use-canvas-store 的 persist 在模块加载（登录前）水合，未登录回落本地缓存；
 * 登录仅广播 nova-auth-changed，store 不重新拉取。修复：订阅 auth change，
 * 登录后重新 loadProjectsFromServer 刷新 store。
 */

const authState = vi.hoisted(() => ({ loggedIn: false, listeners: [] as Array<() => void> }));
const canvasApiState = vi.hoisted(() => ({
  serverProjects: [] as Array<Record<string, unknown>>,
  listCanvasProjects: vi.fn<() => Promise<Array<Record<string, unknown>>>>(),
}));

vi.mock('@/lib/auth', () => ({
  isLoggedIn: () => authState.loggedIn,
  onAuthChange: (listener: () => void) => {
    authState.listeners.push(listener);
    return () => {};
  },
}));

vi.mock('@/lib/canvas-api', () => ({
  listCanvasProjects: canvasApiState.listCanvasProjects,
  createCanvasProject: vi.fn(),
  patchCanvasProject: vi.fn(),
  saveCanvasDocument: vi.fn(),
  softDeleteCanvasProject: vi.fn(),
}));

vi.mock('../../lib/localforage-storage', () => ({
  localForageStorage: {
    getItem: vi.fn().mockResolvedValue(null),
    setItem: vi.fn().mockResolvedValue(undefined),
    removeItem: vi.fn().mockResolvedValue(undefined),
  },
}));

import { useCanvasStore } from '../use-canvas-store';

function serverProject(id: string) {
  return {
    id,
    title: '服务端画布',
    nodes: [],
    connections: [],
    backgroundMode: 'lines',
    showImageInfo: false,
    viewport: { x: 0, y: 0, k: 1 },
    version: 1,
    deletedAt: null,
    createdAt: '2026-08-01T00:00:00.000Z',
    updatedAt: '2026-08-01T00:00:00.000Z',
  };
}

describe('WIN-44 BUG-1 — 登录后画布项目自动重拉', () => {
  beforeEach(() => {
    authState.loggedIn = false;
    // 注意：不重置 authState.listeners —— 模块导入时 store 已注册一次 onAuthChange 监听；
    // 重置会清掉该监听，导致登录事件无人处理（BUG-1 修复的订阅本身）。
    canvasApiState.serverProjects = [];
    canvasApiState.listCanvasProjects.mockReset();
    canvasApiState.listCanvasProjects.mockImplementation(async () => canvasApiState.serverProjects);
  });

  it('未登录时水合回落本地（不拉服务端）', async () => {
    await vi.waitFor(() => expect(useCanvasStore.getState().hydrated).toBe(true));
    expect(canvasApiState.listCanvasProjects).not.toHaveBeenCalled();
    expect(useCanvasStore.getState().projects).toEqual([]);
  });

  it('登录（nova-auth-changed）后自动重新拉取服务端项目列表', async () => {
    await vi.waitFor(() => expect(useCanvasStore.getState().hydrated).toBe(true));
    canvasApiState.serverProjects = [serverProject('srv-1')];
    authState.loggedIn = true;
    for (const listener of authState.listeners) listener();

    await vi.waitFor(() => {
      expect(useCanvasStore.getState().projects.map(p => p.id)).toContain('srv-1');
    });
    expect(canvasApiState.listCanvasProjects).toHaveBeenCalled();
  });
});
