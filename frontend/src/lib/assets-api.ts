'use client';

/**
 * WIN-22 (T10/T13) — 素材服务端 API 数据层（对接 /api/nova/assets）。
 * 取代 IndexedDB（asset-store.ts）作为主存储；IndexedDB 仅保留为导入来源（P1）。
 * 列表响应不含 storage_key（内部细节，ADR-20）。
 */

import { authFetch, readApiError } from '@/lib/auth';
import type { AssetSourceKind } from '@/lib/asset-store';

export interface ServerAsset {
  id: string;
  kind: 'image' | 'text';
  projectId: string | null;
  name?: string | null;
  mimeType?: string | null;
  sizeBytes?: number | null;
  width?: number | null;
  height?: number | null;
  tags: string[];
  note?: string | null;
  sourceKind: AssetSourceKind;
  sourceLabel?: string | null;
  sourceRef?: string | null;
  prompt?: string | null;
  content?: string | null;      // text 素材内容（= prompt）
  hash?: string | null;
  createdAt: string;
  updatedAt: string;
  lastUsedAt?: string | null;
}

export interface AssetListParams {
  projectId?: string;           // __unclassified__ = 未分类
  source?: string;
  q?: string;
  tag?: string;
  sort?: 'newest' | 'oldest' | 'used';
  page?: number;
  size?: number;
}

export interface AssetListResult {
  items: ServerAsset[];
  total: number;
  page: number;
  size: number;
}

export async function fetchAssets(params: AssetListParams = {}): Promise<AssetListResult> {
  const search = new URLSearchParams();
  if (params.projectId) search.set('projectId', params.projectId);
  if (params.source) search.set('source', params.source);
  if (params.q) search.set('q', params.q);
  if (params.tag) search.set('tag', params.tag);
  if (params.sort) search.set('sort', params.sort);
  if (params.page) search.set('page', String(params.page));
  if (params.size) search.set('size', String(params.size));
  const query = search.toString();
  const response = await authFetch(`/api/nova/assets${query ? `?${query}` : ''}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AssetListResult;
}

export interface CreateImageAssetInput {
  file: Blob | File;
  projectId: string;
  name?: string;
  tags?: string[];
  note?: string;
  sourceKind: AssetSourceKind;
  sourceLabel?: string;
  sourceRef?: string;
  prompt?: string;
  width?: number;
  height?: number;
}

export async function createImageAsset(input: CreateImageAssetInput): Promise<ServerAsset> {
  const form = new FormData();
  form.append('file', input.file);
  if (input.projectId) form.append('projectId', input.projectId);
  if (input.name) form.append('name', input.name);
  if (input.tags?.length) form.append('tags', input.tags.join(','));
  if (input.note) form.append('note', input.note);
  form.append('sourceKind', input.sourceKind);
  if (input.sourceLabel) form.append('sourceLabel', input.sourceLabel);
  if (input.sourceRef) form.append('sourceRef', input.sourceRef);
  if (input.prompt) form.append('prompt', input.prompt);
  if (input.width) form.append('width', String(input.width));
  if (input.height) form.append('height', String(input.height));
  const response = await authFetch('/api/nova/assets', {
    method: 'POST',
    body: form,
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerAsset;
}

export interface CreateTextAssetInput {
  content: string;
  projectId: string;
  name?: string;
  tags?: string[];
  note?: string;
  sourceKind: AssetSourceKind;
  sourceLabel?: string;
  sourceRef?: string;
}

export async function createTextAsset(input: CreateTextAssetInput): Promise<ServerAsset> {
  const response = await authFetch('/api/nova/assets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerAsset;
}

export async function fetchAsset(id: string): Promise<ServerAsset> {
  const response = await authFetch(`/api/nova/assets/${encodeURIComponent(id)}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerAsset;
}

export interface UpdateAssetInput {
  name?: string;
  tags?: string[];
  note?: string;
  projectId?: string;
}

export async function updateAsset(id: string, input: UpdateAssetInput): Promise<ServerAsset> {
  const response = await authFetch(`/api/nova/assets/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerAsset;
}

export async function deleteAsset(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/assets/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
}

export async function batchDeleteAssets(ids: string[]): Promise<number> {
  const response = await authFetch('/api/nova/assets/batch-delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids }),
  });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { deleted?: number };
  return data.deleted ?? 0;
}

export async function batchMoveAssets(ids: string[], projectId: string): Promise<number> {
  const response = await authFetch('/api/nova/assets/batch-move', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids, projectId }),
  });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { moved?: number };
  return data.moved ?? 0;
}

/** 素材对象流 URL（鉴权读取，经代理/直连）—— 用于 <img src>。 */
export function assetFileUrl(id: string): string {
  return `/api/nova/assets/${encodeURIComponent(id)}/file`;
}

export function assetDownloadUrl(id: string): string {
  return `/api/nova/assets/${encodeURIComponent(id)}/download`;
}

/** 素材文件名（含扩展名）—— 从 mime 推导。 */
export function assetFileName(asset: ServerAsset): string {
  const base = asset.name || asset.id;
  if (asset.kind === 'text') return `${base}.txt`;
  const mime = asset.mimeType || 'image/png';
  if (mime.includes('jpeg')) return `${base}.jpg`;
  if (mime.includes('webp')) return `${base}.webp`;
  if (mime.includes('gif')) return `${base}.gif`;
  return `${base}.png`;
}

/** 存储健康（设置页状态卡，F-35）。 */
export interface StorageHealth {
  mode: 'minio' | 'disk';
  minioConfigured: boolean;
  bucket?: string | null;
  bucketExists: boolean;
  endpoint: string;
  reachable: boolean;
  fallbackActive: boolean;
  lastCheckedAt: string;
}

export async function fetchStorageHealth(): Promise<StorageHealth> {
  const response = await authFetch('/api/nova/storage/health', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as StorageHealth;
}
