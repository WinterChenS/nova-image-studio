'use client';

/**
 * WIN-39 (WIN-40 T7) — 存量本地数据迁移工具：读本地 IndexedDB/localForage 存量
 * （nova-agent-db 会话 / nova-image 画布）→ 图片字节分批上传（/migration/upload-image，
 * 返回 assetId 改写节点/消息引用）→ 按功能批量导入（/migration/{agent|canvas}/import，幂等）
 * → 本地写 nova-migrated 标记。失败保留本地 + 可重试（FR-7.1/7.2/7.3）。
 *
 * 迁移在「已登录」前提下进行（FR-7.1：未登录不迁移）；重复登录不重复迁移（标记 + 服务端去重）。
 */

import { authFetch, readApiError, isLoggedIn } from '@/lib/auth';
import type { AgentMessage, AgentImageRecord } from '@/lib/agent-chat-config';

// ===== 迁移标记（localStorage）=====

const MIGRATED_MARKER = 'nova-migrated';

export type MigrationFeature = 'agent' | 'canvas' | 'reverse' | 'gif';

export function isFeatureMigrated(feature: MigrationFeature): boolean {
  if (typeof window === 'undefined') return true;
  try {
    const markers = JSON.parse(window.localStorage.getItem(MIGRATED_MARKER) || '{}') as Record<string, boolean>;
    return !!markers[feature];
  } catch {
    return false;
  }
}

export function markFeatureMigrated(feature: MigrationFeature): void {
  if (typeof window === 'undefined') return;
  try {
    const markers = JSON.parse(window.localStorage.getItem(MIGRATED_MARKER) || '{}') as Record<string, boolean>;
    markers[feature] = true;
    window.localStorage.setItem(MIGRATED_MARKER, JSON.stringify(markers));
  } catch {
    // 标记失败不阻塞（服务端去重兜底）
  }
}

// ===== IndexedDB 读取（复用既有 DB 结构）=====

function openDB(name: string, version?: number): Promise<IDBDatabase | null> {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    // S1 修复：不传版本时以当前版本打开（localforage 1.10.0 将 nova-image 建为 v2，
    // 硬编码 version 1 会抛 VersionError → 误判无存量）
    // G1-2：打开失败抛错（与「无存量」区分），由调用方决定是否可写迁移标记
    const req = version == null ? indexedDB.open(name) : indexedDB.open(name, version);
    req.onerror = () => reject(new Error(`本地数据库打开失败: ${name}`));
    req.onsuccess = () => resolve(req.result);
  });
}

function getAllFromStore<T>(db: IDBDatabase, storeName: string): Promise<T[]> {
  return new Promise(resolve => {
    const tx = db.transaction(storeName, 'readonly');
    const req = tx.objectStore(storeName).getAll();
    req.onsuccess = () => resolve((req.result as T[]) || []);
    req.onerror = () => resolve([]);
  });
}

/** 游标读取 key→value 对（localforage 无 keyPath store 的裸值/裸 Blob 需按 key 关联，S1 修复）。 */
function getAllKeyValues<T>(db: IDBDatabase, storeName: string): Promise<Array<{ key: string; value: T }>> {
  return new Promise(resolve => {
    const out: Array<{ key: string; value: T }> = [];
    try {
      const tx = db.transaction(storeName, 'readonly');
      const store = tx.objectStore(storeName);
      const cursor = store.openCursor();
      cursor.onsuccess = () => {
        const c = cursor.result;
        if (c) {
          out.push({ key: String(c.key), value: c.value as T });
          c.continue();
        } else {
          resolve(out);
        }
      };
      cursor.onerror = () => resolve(out);
    } catch {
      resolve(out);
    }
  });
}

/** 判断本地是否有 Agent/画布存量（迁移入口检测，FR-7.1）。 */
export async function hasLocalAgentData(): Promise<boolean> {
  if (isFeatureMigrated('agent')) return false;
  try {
    const db = await openDB('nova-agent-db', 1);
    if (!db) return false;
    const messages = await getAllFromStore<AgentMessage>(db, 'messages');
    db.close();
    return messages.length > 0;
  } catch {
    return false;   // 读取失败视为无存量（仅影响提示条，不写迁移标记）
  }
}

export async function hasLocalCanvasData(): Promise<boolean> {
  if (isFeatureMigrated('canvas')) return false;
  try {
    const data = await readLocalCanvasData();
    return !!data && data.projects.length > 0;
  } catch {
    return false;   // 读取失败视为无存量（仅影响提示条，不写迁移标记）
  }
}

// ===== 图片上传（分批 + 进度）=====

async function uploadMigrationImage(blob: Blob, sourceKind: string): Promise<string> {
  const form = new FormData();
  form.append('file', blob);
  form.append('sourceKind', sourceKind);
  const response = await authFetch('/api/nova/migration/upload-image', { method: 'POST', body: form });
  if (!response.ok) throw await readApiError(response);   // S2 修复：服务端幂等（同 hash 返回已有），不再依赖 409 跳过
  const data = (await response.json()) as { assetId: string; id: string };
  return data.assetId ?? data.id;
}

// ===== Agent 会话迁移 =====

export interface AgentMigrationInput {
  conversations: Array<{
    id: string;
    title: string;
    status?: string;
    imageModel?: string | null;
    webSearch?: boolean;
    pending?: unknown;
    contextSummary?: unknown;
    messages: Array<Record<string, unknown>>;
  }>;
}

export async function readLocalAgentData(): Promise<AgentMigrationInput | null> {
  const db = await openDB('nova-agent-db', 1);
  if (!db) return null;
  const [messages, images, meta] = await Promise.all([
    getAllFromStore<AgentMessage>(db, 'messages'),
    getAllFromStore<AgentImageRecord>(db, 'images'),
    getAllFromStore<{ key: string; value: string }>(db, 'meta'),
  ]);
  db.close();
  if (messages.length === 0 && images.length === 0) return null;

  const imageModel = meta.find(m => m.key === 'imageModel')?.value ?? null;

  const conversation = {
    id: 'local-agent-session',          // 幂等唯一键（同用户去重）
    title: '迁移的 Agent 会话',
    imageModel,
    messages: messages.sort((a, b) => a.createdAt - b.createdAt).map(m => ({
      id: m.id,
      role: m.role,
      text: m.text,
      reasoning: m.reasoning,
      imageIds: (m.imageIds || []).map((imgId: string) => ({ __imgIdRef: imgId })),
      taskId: m.taskId,
      webSearchUsed: m.webSearchUsed,
      withdrawable: m.withdrawable,
    })),
  };
  return { conversations: [conversation] };
}

/** 迁移 Agent 会话：图片上传 → 引用改写 → 导入。返回迁移条数。 */
export async function runAgentMigration(onProgress?: (percent: number, message: string) => void): Promise<number> {
  if (!isLoggedIn()) throw new Error('请先登录');
  onProgress?.(5, '正在读取本地 Agent 会话...');
  let data: AgentMigrationInput | null;
  try {
    data = await readLocalAgentData();
  } catch (e) {
    // G1-2：读取异常 ≠ 无存量 —— 不写迁移标记（避免阶段3 清理误删未迁移数据）
    throw new Error(`读取本地会话失败（数据已保留，可重试）: ${e instanceof Error ? e.message : e}`);
  }
  if (!data) {
    markFeatureMigrated('agent');
    return 0;
  }

  // 1) 收集并上传图片（nova-image-db blobs 优先，缩略图兜底）
  onProgress?.(15, '正在上传会话图片...');
  const blobStore = await openDB('nova-image-db', 2);
  const imageBlobMap = new Map<string, Blob | null>();
  if (blobStore) {
    const blobs = await getAllFromStore<{ jobId: string; index: number; blob: unknown }>(blobStore, 'blobs');
    for (const b of blobs) {
      if (b.blob && isBlobLike(b.blob) && !imageBlobMap.has(b.jobId)) imageBlobMap.set(b.jobId, b.blob as Blob);
    }
    blobStore.close();
  }
  const imageRecords = await readAgentImageRecords();
  const imgIdToAssetId = new Map<string, string>();
  const total = imageRecords.length;
  let done = 0;
  for (const record of imageRecords) {
    let blob = imageBlobMap.get(record.imgId) || null;
    if (!blob && record.thumbnail && record.thumbnail.startsWith('data:')) {
      blob = dataUrlToBlob(record.thumbnail);
    }
    if (blob) {
      // S2 修复：服务端幂等（同 hash 返回已有 assetId），失败则整体报错（可重试，不静默悬挂引用）
      const assetId = await uploadMigrationImage(blob, 'conversation');
      done += 1;
      onProgress?.(15 + Math.floor((done / Math.max(total, 1)) * 50), `正在上传会话图片 ${done}/${total}`);
      imgIdToAssetId.set(record.imgId, assetId);
    }
  }

  // 2) 改写消息 imageIds 引用（imgId → assetId）
  for (const conv of data.conversations) {
    for (const msg of conv.messages) {
      const refs = (msg.imageIds as Array<{ __imgIdRef: string }>) || [];
      msg.imageIds = refs.map(r => imgIdToAssetId.get(r.__imgIdRef) || r.__imgIdRef).filter(Boolean);
    }
  }

  // 3) 导入（幂等：conversation id 去重）
  onProgress?.(75, '正在导入会话到云端...');
  const response = await authFetch('/api/nova/migration/agent/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!response.ok) throw await readApiError(response);
  const summary = (await response.json()) as { created: number; skipped: number };
  markFeatureMigrated('agent');
  onProgress?.(100, 'Agent 会话迁移完成');
  return summary.created ?? 0;
}

async function readAgentImageRecords(): Promise<AgentImageRecord[]> {
  const db = await openDB('nova-agent-db', 1);
  if (!db) return [];
  const records = await getAllFromStore<AgentImageRecord>(db, 'images');
  db.close();
  return records;
}

// ===== 画布迁移 =====

export interface CanvasMigrationInput {
  projects: Array<Record<string, unknown>>;
}

/**
 * 读取本地画布数据（localForage nova-image:canvas_store）。
 * S1 修复：① DB 以当前版本打开（localforage 建为 v2）；② persist 写入的是
 * JSON.stringify({state, version}) 字符串，需先 parse 再取 state.projects。
 */
export async function readLocalCanvasData(): Promise<{ projects: Array<Record<string, unknown>> } | null> {
  const db = await openDB('nova-image');
  if (!db) return null;
  const value = await new Promise<unknown>(resolve => {
    try {
      const tx = db.transaction('canvas_app_state', 'readonly');
      const req = tx.objectStore('canvas_app_state').get('nova-image:canvas_store');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  db.close();
  if (value == null) return null;
  // persist 值为 JSON 字符串（{state:{projects}, version}）；旧版导入可能为对象，兼容两者
  let parsed: unknown = value;
  if (typeof value === 'string') {
    try {
      parsed = JSON.parse(value);
    } catch {
      return null;
    }
  }
  const projects = (parsed as { state?: { projects?: Array<Record<string, unknown>> } })?.state?.projects;
  return projects && projects.length ? { projects } : null;
}

/** Blob 检测（真实浏览器 IDB 返回 Blob 实例；兜底 duck-typing，兼容测试环境/多 realm）。 */
function isBlobLike(value: unknown): value is Blob {
  if (value instanceof Blob) return true;
  return !!value && typeof value === 'object'
    && typeof (value as { arrayBuffer?: unknown }).arrayBuffer === 'function';
}

/** 读取本地画布图片（canvas_image_files，裸 Blob 按 key 关联，S1 修复）。 */
export async function readLocalCanvasImages(): Promise<Map<string, Blob>> {
  const blobMap = new Map<string, Blob>();
  const blobStore = await openDB('nova-image');
  if (blobStore) {
    const entries = await getAllKeyValues<unknown>(blobStore, 'canvas_image_files');
    for (const entry of entries) {
      if (isBlobLike(entry.value) && !blobMap.has(entry.key)) {
        blobMap.set(entry.key, entry.value as Blob);
      }
    }
    blobStore.close();
  }
  return blobMap;
}

/** 迁移画布：节点图片 blob → assets（改写 storageKey→assetId）→ 导入。返回项目数。 */
export async function runCanvasMigration(onProgress?: (percent: number, message: string) => void): Promise<number> {
  if (!isLoggedIn()) throw new Error('请先登录');
  onProgress?.(5, '正在读取本地画布...');
  let data: { projects: Array<Record<string, unknown>> } | null;
  try {
    data = await readLocalCanvasData();
  } catch (e) {
    // G1-2：读取异常 ≠ 无存量 —— 不写迁移标记（避免阶段3 清理误删未迁移数据）
    throw new Error(`读取本地画布失败（数据已保留，可重试）: ${e instanceof Error ? e.message : e}`);
  }
  if (!data) {
    markFeatureMigrated('canvas');
    return 0;
  }

  onProgress?.(15, '正在上传画布图片...');
  const blobMap = await readLocalCanvasImages();

  const storageKeyToAssetId = new Map<string, string>();
  const refs: Array<{ key: string; blob: Blob | null }> = [];
  for (const project of data.projects) {
    collectCanvasStorageKeys(project, (key) => {
      const blob = blobMap.get(key) || null;
      if (blob) refs.push({ key, blob });
    });
  }
  let done = 0;
  const total = refs.length;
  for (const ref of refs) {
    const assetId = await uploadMigrationImage(ref.blob!, 'canvas');
    done += 1;
    onProgress?.(15 + Math.floor((done / Math.max(total, 1)) * 55), `正在上传画布图片 ${done}/${total}`);
    storageKeyToAssetId.set(ref.key, assetId);
  }

  // 改写节点图片引用（storageKey → assetId，ADR-35）
  onProgress?.(78, '正在改写节点图片引用...');
  for (const project of data.projects) {
    rewriteCanvasStorageKeys(project, storageKeyToAssetId);
  }

  onProgress?.(85, '正在导入画布到云端...');
  const response = await authFetch('/api/nova/migration/canvas/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!response.ok) throw await readApiError(response);
  const summary = (await response.json()) as { created: number; skipped: number };
  markFeatureMigrated('canvas');
  onProgress?.(100, '画布迁移完成');
  return summary.created ?? 0;
}

/** 收集节点 storageKey 引用（旧本地 blob 引用，供上传改写，ADR-35）。 */
export function collectCanvasStorageKeys(value: unknown, onKey: (key: string) => void): void {
  if (!value || typeof value !== 'object') return;
  if ('storageKey' in value && typeof value.storageKey === 'string' && value.storageKey.startsWith('image:')) {
    onKey(value.storageKey);
  }
  Object.values(value).forEach(v => (Array.isArray(v) ? v.forEach(c => collectCanvasStorageKeys(c, onKey)) : collectCanvasStorageKeys(v, onKey)));
}

/** 改写节点图片引用：storageKey → assetId（迁移工具核心改写，ADR-35）。 */
export function rewriteCanvasStorageKeys(value: unknown, mapping: Map<string, string>): void {
  if (!value || typeof value !== 'object') return;
  if ('storageKey' in value && typeof value.storageKey === 'string' && mapping.has(value.storageKey)) {
    (value as { storageKey: string }).storageKey = mapping.get(value.storageKey)!;
  }
  Object.values(value).forEach(v => (Array.isArray(v) ? v.forEach(c => rewriteCanvasStorageKeys(c, mapping)) : rewriteCanvasStorageKeys(v, mapping)));
}

function dataUrlToBlob(dataUrl: string): Blob | null {
  try {
    const [head, body] = dataUrl.split(',');
    const mime = /^data:([^;]+)/.exec(head)?.[1] || 'image/png';
    const binary = atob(body);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new Blob([bytes], { type: mime });
  } catch {
    return null;
  }
}
