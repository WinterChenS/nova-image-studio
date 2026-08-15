'use client';

/**
 * WIN-41 (T12/T13/T14, ARCH Part G.2) — 统一历史云端 API 客户端。
 * 对接 HistoryController（histories 表）：反推（reverse 双槽/草稿）+ GIF（状态机/成品）
 * + 统一历史列表/删除/图片访问。属主隔离由服务端保证（AC-10）。
 */

import { authFetch, readApiError } from '@/lib/auth';

// ===== 统一行模型（镜像 HistoryService.toJson）=====

export interface HistoryRow {
  id: string;
  type: 'reverse' | 'gif';
  status: string;
  title: string;
  payload: Record<string, unknown> | null;
  imageIds: string[];
  taskId: string | null;
  error: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HistoryPage {
  items: HistoryRow[];
  nextBefore: string | null;
}

export interface ReverseDraftInput {
  text?: string;
  imageIds?: string[];
  model?: string;
  mode?: string;
}

export interface GifJobInput {
  prompt: string;
  model?: string;
  loop?: boolean;
  closedLoop?: boolean;
  frameDelayMs?: number;
  loopCount?: number;
  framePadding?: number;
  gptImageQuality?: string;
  gptImageStyle?: string;
  gptImageBackground?: string;
  refImageAssetIds?: string[];
}

export interface GifJobPatch {
  status: string;
  taskId?: string;
  gridImageAssetId?: string;
  error?: string;
}

// ===== 统一历史列表 / 删除 / 图片（C5/C6）=====

/** 历史分页列表（type=reverse|gif；before 游标 + limit）。 */
export async function listHistories(
  type: 'reverse' | 'gif',
  params: { before?: string; limit?: number } = {},
): Promise<HistoryPage> {
  const search = new URLSearchParams();
  search.set('type', type);
  if (params.before) search.set('before', params.before);
  if (params.limit) search.set('limit', String(params.limit));
  const response = await authFetch(`/api/nova/histories?${search.toString()}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as HistoryPage;
}

/** 删除历史记录（gif 联动删除关联素材，ADR-42）。 */
export async function deleteHistory(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/histories/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
}

/** 历史图片鉴权访问 URL（网格/成品，source_kind=gif|reverse-prompt）。 */
export function historyImageUrl(assetId: string): string {
  return `/api/nova/histories/images/${encodeURIComponent(assetId)}`;
}

// ===== 反推（T12，type=reverse）=====

/** 保存反推 completed 记录（双槽语义由查询端承载）。 */
export async function saveReverseRecord(input: {
  text: string;
  model?: string;
  mode?: string;
}): Promise<HistoryRow> {
  const response = await authFetch('/api/nova/reverse/records', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as HistoryRow;
}

/** 反推双槽：最近 limit 条 completed（默认 2）。 */
export async function listReverseRecords(limit = 2): Promise<HistoryRow[]> {
  const response = await authFetch(`/api/nova/reverse/records?limit=${limit}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { items?: HistoryRow[] };
  return data.items ?? [];
}

/** 反推草稿 upsert（每用户至多一条；空 text+空 imageIds → 清除）。 */
export async function saveReverseDraft(input: ReverseDraftInput): Promise<HistoryRow | null> {
  const response = await authFetch('/api/nova/reverse/draft', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as HistoryRow | { ok: true; cleared: true };
  if ('cleared' in data) return null;
  return data as HistoryRow;
}

/** 读取反推草稿（无则 null）。 */
export async function getReverseDraft(): Promise<HistoryRow | null> {
  const response = await authFetch('/api/nova/reverse/draft', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { draft?: HistoryRow | null };
  return data.draft ?? null;
}

// ===== GIF（T13，type=gif）=====

/** 创建 GIF job（status=idle）。 */
export async function createGifJob(input: GifJobInput): Promise<HistoryRow> {
  const response = await authFetch('/api/nova/gif/jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as HistoryRow;
}

/** GIF job 状态查询（中断恢复：刷新/换端按服务端状态恢复，AC-4）。 */
export async function getGifJob(id: string): Promise<HistoryRow> {
  const response = await authFetch(`/api/nova/gif/jobs/${encodeURIComponent(id)}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as HistoryRow;
}

/** GIF 状态变迁（轻量 PATCH，非法迁移 → 409）。 */
export async function patchGifJob(id: string, patch: GifJobPatch): Promise<HistoryRow> {
  const response = await authFetch(`/api/nova/gif/jobs/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as HistoryRow;
}

/** 上传 GIF 成品（multipart → assets source_kind=gif，status→done）。 */
export async function uploadGifResult(id: string, file: Blob, mimeType?: string): Promise<HistoryRow> {
  const form = new FormData();
  form.append('file', file, `gif-${id}.gif`);
  if (mimeType) form.append('mimeType', mimeType);
  const response = await authFetch(`/api/nova/gif/jobs/${encodeURIComponent(id)}/result`, {
    method: 'POST',
    body: form,
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as HistoryRow;
}
