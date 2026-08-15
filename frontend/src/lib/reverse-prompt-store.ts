// 反推结果的 IndexedDB 持久化层
// 数据库: nova-reverse-db (v1)
// store: reverse-results (keyPath: 'slot')
// 保存文字结果和当前输入图草稿。
//
// WIN-41 (T12) 云端化：在 IDB 之上叠加 histories type=reverse 云端同步
// （completed 双槽 + draft 草稿），服务端优先读取实现跨端一致（AC-8）；
// 云端不可用时回退 IDB 本地缓存。

export interface StoredReverseResult {
  slot: 'current' | 'previous';
  text: string;
  model: string;
  mode: string;
  aborted?: boolean;
  timestamp: number;
}

export interface StoredReverseDraft {
  slot: 'draft';
  file: {
    id: string;
    name: string;
    preview: string;
    dataUrl: string;
    mimeType: string;
    badge?: string;
  } | null;
  timestamp: number;
}

const DB_NAME = 'nova-reverse-db';
const DB_VERSION = 1;
const STORE_NAME = 'reverse-results';

function openReverseDB(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null);

  return new Promise((resolve) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onerror = () => resolve(null);
    req.onsuccess = () => resolve(req.result);
    req.onupgradeneeded = (e) => {
      const db = (e.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'slot' });
      }
    };
  });
}

/** 从 IndexedDB 加载 current / previous 两条记录（本地回退路径） */
async function loadReverseResultsLocal(): Promise<{
  current: StoredReverseResult | null;
  previous: StoredReverseResult | null;
  draft: StoredReverseDraft | null;
}> {
  const db = await openReverseDB();
  if (!db) return { current: null, previous: null, draft: null };

  return new Promise((resolve) => {
    const tx = db.transaction(STORE_NAME, 'readonly');
    const store = tx.objectStore(STORE_NAME);

    let current: StoredReverseResult | null = null;
    let previous: StoredReverseResult | null = null;
    let draft: StoredReverseDraft | null = null;

    const getReq = store.get('current');
    getReq.onsuccess = () => {
      current = (getReq.result as StoredReverseResult) ?? null;
    };

    const getReq2 = store.get('previous');
    getReq2.onsuccess = () => {
      previous = (getReq2.result as StoredReverseResult) ?? null;
    };

    const getReq3 = store.get('draft');
    getReq3.onsuccess = () => {
      draft = (getReq3.result as StoredReverseDraft) ?? null;
    };

    tx.oncomplete = () => resolve({ current, previous, draft });
    tx.onerror = () => resolve({ current: null, previous: null, draft: null });
  });
}

/** 从云端（histories type=reverse）加载双槽 + 草稿；未登录/失败返回 null。 */
async function loadReverseResultsCloud(): Promise<{
  current: StoredReverseResult | null;
  previous: StoredReverseResult | null;
  draft: StoredReverseDraft | null;
} | null> {
  const { isLoggedIn } = await import('@/lib/auth');
  if (!isLoggedIn()) return null;
  const { listReverseRecords, getReverseDraft, historyImageUrl } = await import('@/lib/histories-api');
  const records = await listReverseRecords(2);
  const current = records[0] ? toStoredResult(records[0]) : null;
  const previous = records[1] ? toStoredResult(records[1]) : null;
  let draft: StoredReverseDraft['file'] | null = null;
  try {
    const cloudDraft = await getReverseDraft();
    if (cloudDraft) {
      const assetId = cloudDraft.imageIds?.[0];
      if (assetId) {
        const dataUrl = await fetchAssetDataUrl(historyImageUrl(assetId));
        if (dataUrl) {
          draft = {
            id: assetId,
            name: '草稿图',
            preview: historyImageUrl(assetId),
            dataUrl,
            mimeType: dataUrlMime(dataUrl) || 'image/png',
          };
        }
      }
    }
  } catch {
    // 草稿云端读取失败不阻塞双槽恢复
  }
  return {
    current,
    previous,
    draft: draft ? { slot: 'draft', file: draft, timestamp: Date.now() } : null,
  };
}

/** 加载反推结果：云端优先（双槽/草稿跨端一致，AC-8），失败回退 IDB。 */
export async function loadReverseResults(): Promise<{
  current: StoredReverseResult | null;
  previous: StoredReverseResult | null;
  draft: StoredReverseDraft | null;
}> {
  try {
    const cloud = await loadReverseResultsCloud();
    if (cloud) return cloud;
  } catch {
    // 未登录/云端不可用 → IDB 本地回退
  }
  return loadReverseResultsLocal();
}

/** WIN-41（T14）：仅读取本地 IDB 反推存量（迁移检测/迁移用，不触云端）。 */
export async function readLocalReverseData(): Promise<{
  current: StoredReverseResult | null;
  previous: StoredReverseResult | null;
  draft: StoredReverseDraft | null;
}> {
  return loadReverseResultsLocal();
}

/** WIN-41（T14）：本地是否存在反推存量。 */
export async function hasLocalReverseData(): Promise<boolean> {
  try {
    const data = await loadReverseResultsLocal();
    return !!(data.current?.text || data.previous?.text || data.draft?.file);
  } catch {
    return false;
  }
}

/** IDB 本地保存（回退路径）。 */
async function saveReverseResultLocal(result: StoredReverseResult): Promise<void> {
  const db = await openReverseDB();
  if (!db) return;

  return new Promise((resolve) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).put(result);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
  });
}

/** 保存单条记录到指定槽位（本地 + 云端 completed，best-effort）。 */
export async function saveReverseResult(result: StoredReverseResult): Promise<void> {
  await saveReverseResultLocal(result);
  try {
    const { saveReverseRecord } = await import('@/lib/histories-api');
    await saveReverseRecord({
      text: result.text,
      model: result.model,
      mode: result.mode,
    });
  } catch {
    // 云端失败保留本地（后续保存幂等覆盖）
  }
}

/** 清除指定槽位（本地语义；云端历史由统一历史列表管理，保留可查）。 */
export async function clearReverseResult(slot: 'current' | 'previous'): Promise<void> {
  const db = await openReverseDB();
  if (!db) return;

  return new Promise((resolve) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).delete(slot);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
  });
}

/** IDB 本地草稿保存（回退路径）。 */
async function saveReverseDraftLocal(file: StoredReverseDraft['file']): Promise<void> {
  const db = await openReverseDB();
  if (!db) return;

  return new Promise((resolve) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).put({ slot: 'draft', file, timestamp: Date.now() });
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
  });
}

/** 保存当前输入图草稿（本地 + 云端：输入图上传 assets + draft upsert，best-effort）。 */
export async function saveReverseDraft(file: StoredReverseDraft['file']): Promise<void> {
  await saveReverseDraftLocal(file);
  try {
    const { saveReverseDraft: putCloudDraft } = await import('@/lib/histories-api');
    const { createImageAsset } = await import('@/lib/assets-api');
    let imageIds: string[] = [];
    if (file) {
      const blob = dataUrlToBlob(file.dataUrl, file.mimeType);
      if (blob) {
        const asset = await createImageAsset({
          file: blob,
          projectId: '',
          name: file.name || '反推草稿图',
          sourceKind: 'reverse-prompt',
          sourceLabel: '反推提示词上传图',
        });
        imageIds = [asset.id];
      }
    }
    await putCloudDraft({ text: '', imageIds });
  } catch {
    // 云端失败保留本地
  }
}

/** IDB 本地草稿清除（回退路径）。 */
async function clearReverseDraftLocal(): Promise<void> {
  const db = await openReverseDB();
  if (!db) return;

  return new Promise((resolve) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).delete('draft');
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
  });
}

/** 清除当前输入图草稿（本地 + 云端清空）。 */
export async function clearReverseDraft(): Promise<void> {
  await clearReverseDraftLocal();
  try {
    const { saveReverseDraft: putCloudDraft } = await import('@/lib/histories-api');
    await putCloudDraft({ text: '', imageIds: [] });
  } catch {
    // 云端失败保留本地
  }
}

// ===== 工具 =====

function toStoredResult(row: {
  text?: string;
  payload?: Record<string, unknown> | null;
  model?: string;
  mode?: string;
}): StoredReverseResult {
  const text = row.text || String(row.payload?.text ?? '');
  return {
    slot: 'current',
    text,
    model: String(row.payload?.model ?? row.model ?? ''),
    mode: String(row.payload?.mode ?? row.mode ?? 'simple'),
    timestamp: Date.now(),
  };
}

async function fetchAssetDataUrl(url: string): Promise<string | null> {
  const { authFetch } = await import('@/lib/auth');
  try {
    const response = await authFetch(url);
    if (!response.ok) return null;
    const blob = await response.blob();
    return await blobToDataUrl(blob);
  } catch {
    return null;
  }
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => resolve('');
    reader.readAsDataURL(blob);
  });
}

function dataUrlMime(dataUrl: string): string {
  const match = /^data:([^;]+);/.exec(dataUrl);
  return match ? match[1] : '';
}

function dataUrlToBlob(dataUrl: string, mimeType: string): Blob | null {
  const comma = dataUrl.indexOf(',');
  if (comma < 0) return null;
  try {
    const binary = atob(dataUrl.slice(comma + 1));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new Blob([bytes], { type: mimeType || 'image/png' });
  } catch {
    return null;
  }
}
