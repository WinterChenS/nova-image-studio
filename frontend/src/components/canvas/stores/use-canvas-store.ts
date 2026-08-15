import { create } from "zustand";
import { persist, type PersistStorage, type StorageValue } from "zustand/middleware";

import { nanoid } from "nanoid";
import { localForageStorage } from "../lib/localforage-storage";
import type { CanvasBackgroundMode } from "../lib/canvas-theme";
import type { CanvasConnection, CanvasNodeData, ViewportTransform } from "../types";
import {
  createCanvasProject,
  getCanvasProject,
  listCanvasProjects,
  patchCanvasProject,
  saveCanvasDocument,
  softDeleteCanvasProject,
  type ServerCanvasProject,
} from "@/lib/canvas-api";
import { isLoggedIn, onAuthChange } from "@/lib/auth";

export type CanvasProject = {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  nodes: CanvasNodeData[];
  connections: CanvasConnection[];
  backgroundMode: CanvasBackgroundMode;
  showImageInfo: boolean;
  viewport: ViewportTransform;
  version?: number;
};

type CanvasProjectPatch = Partial<Pick<CanvasProject, "nodes" | "connections" | "backgroundMode" | "showImageInfo" | "viewport">>;
type CanvasProjectUpdateOptions = { touchUpdatedAt?: boolean };
export type CanvasSaveStatus = "saved" | "saving" | "error";

type CanvasStore = {
  hydrated: boolean;
  saveStatus: CanvasSaveStatus;
  projects: CanvasProject[];
  createProject: (title?: string) => string;
  importProject: (project: Partial<CanvasProject>) => string;
  openProject: (id: string) => CanvasProject | null;
  renameProject: (id: string, title: string) => void;
  deleteProjects: (ids: string[]) => void;
  replaceProjects: (projects: CanvasProject[]) => void;
  updateProject: (id: string, patch: CanvasProjectPatch, options?: CanvasProjectUpdateOptions) => void;
};

const initialViewport: ViewportTransform = { x: 0, y: 0, k: 1 };
const CANVAS_STORE_KEY = "nova-image:canvas_store";
type PersistedCanvasState = Pick<CanvasStore, "projects">;
let saveTimer: ReturnType<typeof setTimeout> | null = null;
let queuedPersistState: PersistedCanvasState | null = null;
let queuedPersistValue: { name: string; value: StorageValue<CanvasStore> } | null = null;

/** 每项目最近一次已成功保存的文档快照（id → JSON），用于去重保存（仅差异项目提交）。 */
const lastSavedDocs = new Map<string, string>();

function projectDocSnapshot(p: CanvasProject): string {
  return JSON.stringify({
    title: p.title,
    nodes: p.nodes,
    connections: p.connections,
    backgroundMode: p.backgroundMode,
    showImageInfo: p.showImageInfo,
    viewport: p.viewport,
  });
}

function toStoreProject(server: ServerCanvasProject): CanvasProject {
  return {
    id: server.id,
    title: server.title,
    createdAt: server.createdAt,
    updatedAt: server.updatedAt,
    nodes: server.nodes || [],
    connections: server.connections || [],
    backgroundMode: server.backgroundMode || "lines",
    showImageInfo: !!server.showImageInfo,
    viewport: server.viewport || initialViewport,
    version: server.version ?? 1,
  };
}

/** 将项目整文档提交到服务端（新建走 create+PUT 幂等；已有走 PUT version 自增）。 */
async function saveProjectToServer(project: CanvasProject): Promise<void> {
  try {
    await saveCanvasDocument(project.id, {
      title: project.title,
      nodes: project.nodes,
      connections: project.connections,
      viewport: project.viewport,
      backgroundMode: project.backgroundMode,
      showImageInfo: project.showImageInfo,
      version: project.version ?? 1,
    });
    lastSavedDocs.set(project.id, projectDocSnapshot(project));
  } catch (err) {
    // WIN-42 (T16, A8)：画布版本冲突 → 409（其他设备已修改）。前端提示 + 从服务端
    // 拉取最新版本并提示刷新，避免静默覆盖。冲突视为「已提示」，不再写入本地保存快照。
    if (isVersionConflict(err)) {
      dispatchConflictToast();
      await refreshProjectFromServer(project.id);
      return;
    }
    throw err;
  }
}

/** 409 VERSION_CONFLICT 判定（服务端 HttpErrorException 形状 {error, code}）。 */
function isVersionConflict(err: unknown): boolean {
  if (err && typeof err === 'object' && 'code' in err) {
    return (err as { code?: string }).code === 'VERSION_CONFLICT';
  }
  const msg = err instanceof Error ? err.message : String(err);
  return msg.includes('VERSION_CONFLICT') || msg.includes('版本');
}

/** 冲突后从服务端重拉项目文档，覆盖本地（防止持续用陈旧版本覆盖）。 */
async function refreshProjectFromServer(projectId: string): Promise<void> {
  try {
    const server = await getCanvasProject(projectId);
    const next = toStoreProject(server);
    useCanvasStore.setState(state => ({
      projects: state.projects.map(p => (p.id === projectId ? next : p)),
    }));
    lastSavedDocs.set(projectId, projectDocSnapshot(next));
  } catch {
    // 重拉失败不阻断（保留本地，等待用户手动刷新）
  }
}

/** 全局冲突提示（T16 前端提示；上层 toast 由订阅方监听）。 */
function dispatchConflictToast(): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent('canvas-version-conflict', {
    detail: { message: '画布已在其他设备被修改，已刷新为服务端最新版本。若继续编辑请谨慎。' },
  }));
}

async function persistQueuedCanvasState() {
  const queued = queuedPersistValue;
  if (!queued) return;
  queuedPersistValue = null;
  const nextState = queued.value.state as PersistedCanvasState;
  try {
    // 按项目差异提交（仅变化的项目发请求；未登录时降级本地，离线可继续编辑）
    const changed = nextState.projects.filter(p => lastSavedDocs.get(p.id) !== projectDocSnapshot(p));
    if (changed.length > 0 && isLoggedIn()) {
      await Promise.all(changed.map(saveProjectToServer));
    } else if (changed.length > 0) {
      // 未登录/服务端不可用 → 本地临时缓冲（非离线主存储，保存状态机 error 由上层提示）
      await localForageStorage.setItem(queued.name, JSON.stringify(queued.value));
    }
    if (!queuedPersistValue) useCanvasStore.setState({ saveStatus: "saved" });
  } catch {
    useCanvasStore.setState({ saveStatus: "error" });
  }
}

export async function flushPendingCanvasSave() {
  if (saveTimer) {
    clearTimeout(saveTimer);
    saveTimer = null;
  }
  await persistQueuedCanvasState();
}

/** WIN-39（T6）：从服务端加载项目列表（登录态），失败/未登录降级本地 IndexedDB。 */
async function loadProjectsFromServer(): Promise<CanvasProject[] | null> {
  if (!isLoggedIn()) return null;
  try {
    const items = await listCanvasProjects();
    const projects = items.map(toStoreProject);
    for (const p of projects) lastSavedDocs.set(p.id, projectDocSnapshot(p));
    return projects;
  } catch {
    return null;
  }
}

const canvasStorage: PersistStorage<CanvasStore> = {
  getItem: async (name) => {
    const serverProjects = await loadProjectsFromServer();
    if (serverProjects) {
      const state = { projects: serverProjects } as PersistedCanvasState;
      queuedPersistState = state;
      return { state, version: 0 } as StorageValue<CanvasStore>;
    }
    const value = await localForageStorage.getItem(name);
    if (!value) return null;
    const parsed = JSON.parse(value) as StorageValue<CanvasStore>;
    queuedPersistState = parsed.state as PersistedCanvasState;
    return parsed;
  },
  setItem: (name, value) => {
    const nextState = value.state as PersistedCanvasState;
    if (queuedPersistState && queuedPersistState.projects === nextState.projects) return;
    queuedPersistState = nextState;
    queuedPersistValue = { name, value };
    useCanvasStore.setState({ saveStatus: "saving" });
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      saveTimer = null;
      void persistQueuedCanvasState();
    }, 400);
  },
  removeItem: (name) => localForageStorage.removeItem(name),
};

export const useCanvasStore = create<CanvasStore>()(
  persist(
    (set, get) => ({
      hydrated: false,
      saveStatus: "saved",
      projects: [],
      createProject: (title = "未命名画布") => {
        const now = new Date().toISOString();
        const id = nanoid();
        const project: CanvasProject = {
          id,
          title,
          createdAt: now,
          updatedAt: now,
          nodes: [],
          connections: [],
          backgroundMode: "lines",
          showImageInfo: false,
          viewport: initialViewport,
          version: 1,
        };
        set((state) => ({ projects: [project, ...state.projects] }));
        // WIN-39：新建即建服务端项目（客户端 id 稳定，后续差异保存走 PUT）
        if (isLoggedIn()) {
          void createCanvasProject(project.title, id)
            .then(server => {
              lastSavedDocs.set(id, projectDocSnapshot(project));
              useCanvasStore.setState((state) => ({
                projects: state.projects.map(p => p.id === id ? { ...p, version: server.version ?? 1 } : p),
              }));
            })
            .catch(() => useCanvasStore.setState({ saveStatus: "error" }));
        }
        return id;
      },
      importProject: (source) => {
        const now = new Date().toISOString();
        const project: CanvasProject = {
          id: nanoid(),
          title: source.title || "导入画布",
          createdAt: source.createdAt || now,
          updatedAt: now,
          nodes: source.nodes || [],
          connections: source.connections || [],
          backgroundMode: source.backgroundMode || "lines",
          showImageInfo: source.showImageInfo || false,
          viewport: source.viewport || initialViewport,
          version: source.version || 1,
        };
        set((state) => ({ projects: [project, ...state.projects] }));
        if (isLoggedIn()) {
          void createCanvasProject(project.title, project.id)
            .then(() => { lastSavedDocs.set(project.id, projectDocSnapshot(project)); })
            .catch(() => { /* 差异保存兜底 */ });
        }
        return project.id;
      },
      openProject: (id) => {
        return get().projects.find((item) => item.id === id) || null;
      },
      renameProject: (id, title) => {
        const nextTitle = title.trim() || undefined;
        set((state) => ({
          projects: state.projects.map((project) => (project.id === id ? { ...project, title: nextTitle || project.title, updatedAt: new Date().toISOString() } : project)),
        }));
        if (isLoggedIn() && nextTitle) {
          void patchCanvasProject(id, { title: nextTitle }).catch(() => useCanvasStore.setState({ saveStatus: "error" }));
        }
      },
      deleteProjects: (ids) =>
        set((state) => {
          const projects = state.projects.filter((project) => !ids.includes(project.id));
          if (isLoggedIn()) {
            ids.forEach(id => { void softDeleteCanvasProject(id).catch(() => {}); lastSavedDocs.delete(id); });
          }
          return { projects };
        }),
      replaceProjects: (projects) => set({ projects }),
      updateProject: (id, patch, options) =>
        set((state) => ({
          projects: state.projects.map((project) => {
            if (project.id !== id) return project;
            const changed = Object.entries(patch).some(([key, value]) => project[key as keyof CanvasProject] !== value);
            if (!changed) return project;
            return {
              ...project,
              ...patch,
              updatedAt: options?.touchUpdatedAt === false ? project.updatedAt : new Date().toISOString(),
            };
          }),
        })),
    }),
    {
      name: CANVAS_STORE_KEY,
      storage: canvasStorage,
      partialize: (state) =>
        ({
          projects: state.projects,
        }) as StorageValue<CanvasStore>["state"],
      onRehydrateStorage: () => () => {
        useCanvasStore.setState({ hydrated: true });
      },
    },
  ),
);

// WIN-44 BUG-1 修复：persist 在模块加载（登录前）水合，未登录时回落本地缓存；
// 登录后不会重新拉取服务端。订阅 nova-auth-changed：登录成功后重新
// loadProjectsFromServer 刷新 store（服务端为登录态唯一数据源，与 getItem 语义一致）。
if (typeof window !== "undefined") {
  onAuthChange(() => {
    if (!isLoggedIn()) return;
    void loadProjectsFromServer().then((projects) => {
      if (projects) {
        useCanvasStore.setState({ projects, hydrated: true });
      }
    });
  });
}
