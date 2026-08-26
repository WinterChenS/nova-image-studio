import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * WIN-42 (T16, A8) — 画布版本冲突：保存返回 409 VERSION_CONFLICT 时，
 * store 不静默覆盖：dispatch 冲突事件（前端提示）+ 从服务端重拉最新版本覆盖本地。
 */

const authState = vi.hoisted(() => ({ loggedIn: true }));
const canvasApiState = vi.hoisted(() => ({
  saveCanvasDocument: vi.fn<(id: string, input: { version?: number }) => Promise<unknown>>(),
  getCanvasProject: vi.fn<() => Promise<unknown>>(),
}));

vi.mock('@/lib/auth', () => ({
  isLoggedIn: () => authState.loggedIn,
  onAuthChange: () => () => {},
}));

vi.mock('@/lib/canvas-api', () => ({
  listCanvasProjects: vi.fn(async () => []),
  createCanvasProject: vi.fn(async () => ({ version: 1 })),
  patchCanvasProject: vi.fn(async () => ({})),
  saveCanvasDocument: canvasApiState.saveCanvasDocument,
  getCanvasProject: canvasApiState.getCanvasProject,
  softDeleteCanvasProject: vi.fn(async () => {}),
}));

vi.mock('../../lib/localforage-storage', () => ({
  localForageStorage: {
    getItem: vi.fn().mockResolvedValue(null),
    setItem: vi.fn().mockResolvedValue(undefined),
    removeItem: vi.fn().mockResolvedValue(undefined),
  },
}));

import { flushPendingCanvasSave, useCanvasStore, type CanvasProject } from '../use-canvas-store';

function project(overrides: Partial<CanvasProject> = {}): CanvasProject {
  return {
    id: 'p1',
    title: '画布',
    createdAt: '2026-08-01T00:00:00.000Z',
    updatedAt: '2026-08-01T00:00:00.000Z',
    nodes: [],
    connections: [],
    backgroundMode: 'lines',
    showImageInfo: false,
    viewport: { x: 0, y: 0, k: 1 },
    version: 3,
    ...overrides,
  };
}

describe('WIN-42 T16 — 画布版本冲突（409 + 前端提示 + 服务端重拉）', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-02T08:00:00.000Z'));
    canvasApiState.saveCanvasDocument.mockReset();
    canvasApiState.getCanvasProject.mockReset();
    useCanvasStore.setState({ hydrated: true, saveStatus: 'saved', projects: [project()] });
  });

  it('保存携带 version，冲突时 dispatch canvas-version-conflict 事件', async () => {
    canvasApiState.saveCanvasDocument.mockRejectedValue({
      code: 'VERSION_CONFLICT',
      error: '画布已在其他设备被修改',
    });
    canvasApiState.getCanvasProject.mockResolvedValue({
      id: 'p1',
      title: '服务端新版本',
      nodes: [{ id: 'n1' }],
      connections: [],
      backgroundMode: 'lines',
      showImageInfo: false,
      viewport: { x: 0, y: 0, k: 1 },
      version: 7,
      deletedAt: null,
      createdAt: '2026-08-01T00:00:00.000Z',
      updatedAt: '2026-08-02T08:00:00.000Z',
    });

    let conflictEvent: CustomEvent | null = null;
    const handler = (e: Event) => { conflictEvent = e as CustomEvent; };
    window.addEventListener('canvas-version-conflict', handler);

    useCanvasStore.getState().renameProject('p1', '改名触发保存');
    await flushPendingCanvasSave();
    vi.runAllTimers();

    // 保存请求带 version（冲突检测依据）
    expect(canvasApiState.saveCanvasDocument).toHaveBeenCalledWith('p1', expect.objectContaining({ version: 3 }));
    // 前端提示事件已派发
    expect(conflictEvent).not.toBeNull();
    // 本地已刷新为服务端最新版本（version=7，标题=服务端新版本）
    expect(useCanvasStore.getState().openProject('p1')?.version).toBe(7);
    expect(useCanvasStore.getState().openProject('p1')?.title).toBe('服务端新版本');

    window.removeEventListener('canvas-version-conflict', handler);
  });

  it('无冲突时正常保存并更新本地快照', async () => {
    canvasApiState.saveCanvasDocument.mockResolvedValue({ version: 4 });
    useCanvasStore.getState().renameProject('p1', '正常改名');
    await flushPendingCanvasSave();
    vi.runAllTimers();
    expect(canvasApiState.saveCanvasDocument).toHaveBeenCalled();
    expect(useCanvasStore.getState().openProject('p1')?.title).toBe('正常改名');
  });

  // ===== 核对报告 🔴 阻塞项复现：保存成功后必须回写服务端自增的 version =====

  it('单设备连续两次保存携带递增 version（不触发 409 / 不重拉）', async () => {
    // 服务端语义：PUT 成功 → 返回自增后的版本（3→4→5）
    canvasApiState.saveCanvasDocument.mockImplementation(async (_id: string, input: { version?: number }) => ({
      id: _id,
      version: (input.version ?? 0) + 1,
    }));

    const renameAndFlush = async (title: string) => {
      useCanvasStore.getState().renameProject('p1', title);
      await flushPendingCanvasSave();
      vi.runAllTimers();
    };

    await renameAndFlush('第一次改名');   // 携带 version=3，服务端 → 4
    await renameAndFlush('第二次改名');   // 必须携带 version=4（回写后），服务端 → 5

    const sentVersions = canvasApiState.saveCanvasDocument.mock.calls
      .map(([, input]) => (input as { version?: number }).version);
    expect(sentVersions).toEqual([3, 4]);
    // 本地 version 已回写为服务端最新值
    expect(useCanvasStore.getState().openProject('p1')?.version).toBe(5);
    // 未发生冲突：不应派发冲突事件、不应触发兜底重拉
    expect(canvasApiState.getCanvasProject).not.toHaveBeenCalled();
  });

  it('importProject 创建成功后回写服务端返回的 version', async () => {
    const { createCanvasProject } = await import('@/lib/canvas-api');
    vi.mocked(createCanvasProject).mockResolvedValue({
      id: 'imp-1',
      title: '导入画布',
      nodes: [],
      connections: [],
      backgroundMode: 'lines',
      showImageInfo: false,
      viewport: { x: 0, y: 0, k: 1 },
      version: 1,
      deletedAt: null,
      createdAt: '2026-08-02T08:00:00.000Z',
      updatedAt: '2026-08-02T08:00:00.000Z',
    });

    const id = useCanvasStore.getState().importProject({ title: '导入画布' });
    await vi.waitFor(() => {
      expect(useCanvasStore.getState().openProject(id)?.version).toBe(1);
    });
    // 后续差异保存应携带服务端确认的 version=1（而非本地旧值）
    canvasApiState.saveCanvasDocument.mockImplementation(async (_id: string, input: { version?: number }) => ({
      version: (input.version ?? 0) + 1,
    }));
    useCanvasStore.getState().renameProject(id, '导入后改名');
    await flushPendingCanvasSave();
    vi.runAllTimers();
    const firstSaveInput = canvasApiState.saveCanvasDocument.mock.calls[0]?.[1] as { version?: number };
    expect(firstSaveInput?.version).toBe(1);
  });
});
