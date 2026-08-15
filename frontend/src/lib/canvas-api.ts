'use client';

/**
 * WIN-39 (WIN-40 T6) — 画布云端持久化 API 客户端（对接 /api/nova/canvas/*）。
 * 项目结构（nodes/connections/viewport）JSONB 整文档；节点图片引用 assets.id
 * （imageRef=assetId，ADR-35 素材引用化）；图片字节经 assets 鉴权链路。
 */

import { authFetch, readApiError } from '@/lib/auth';
import type { CanvasBackgroundMode } from '@/components/canvas/lib/canvas-theme';
import type { CanvasConnection, CanvasNodeData, ViewportTransform } from '@/components/canvas/types';

export interface ServerCanvasProject {
  id: string;
  title: string;
  nodes: CanvasNodeData[] | null;
  connections: CanvasConnection[] | null;
  backgroundMode: CanvasBackgroundMode | null;
  showImageInfo: boolean;
  viewport: ViewportTransform | null;
  version: number;
  deletedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CanvasProjectSaveInput {
  title?: string;
  nodes?: CanvasNodeData[];
  connections?: CanvasConnection[];
  viewport?: ViewportTransform;
  backgroundMode?: CanvasBackgroundMode;
  showImageInfo?: boolean;
  version?: number;
}

export async function listCanvasProjects(includeDeleted = false): Promise<ServerCanvasProject[]> {
  const query = includeDeleted ? '?includeDeleted=true' : '';
  const response = await authFetch(`/api/nova/canvas/projects${query}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { items: ServerCanvasProject[] };
  return data.items ?? [];
}

export async function createCanvasProject(title?: string, clientId?: string): Promise<ServerCanvasProject> {
  const body: Record<string, string> = {};
  if (title) body.title = title;
  if (clientId) body.id = clientId;
  const response = await authFetch('/api/nova/canvas/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerCanvasProject;
}

export async function getCanvasProject(id: string): Promise<ServerCanvasProject> {
  const response = await authFetch(`/api/nova/canvas/projects/${encodeURIComponent(id)}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerCanvasProject;
}

/** 整文档保存（防抖提交；version 自增，T16 A8：服务端版本冲突返回 409 + 前端提示）。 */
export async function saveCanvasDocument(id: string, input: CanvasProjectSaveInput): Promise<ServerCanvasProject> {
  const response = await authFetch(`/api/nova/canvas/projects/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerCanvasProject;
}

export async function patchCanvasProject(id: string, patch: { title?: string; backgroundMode?: string; showImageInfo?: boolean }): Promise<ServerCanvasProject> {
  const response = await authFetch(`/api/nova/canvas/projects/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerCanvasProject;
}

export async function softDeleteCanvasProject(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/canvas/projects/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
}

export async function restoreCanvasProject(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/canvas/projects/${encodeURIComponent(id)}/restore`, { method: 'POST' });
  if (!response.ok) throw await readApiError(response);
}

/** T16：清空回收站（硬删全部软删项目）。返回清空条数。 */
export async function emptyCanvasTrash(): Promise<number> {
  const response = await authFetch('/api/nova/canvas/projects/trash', { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { removed?: number };
  return data.removed ?? 0;
}

/** 画布图片上传（→ assets source_kind='canvas'，返回 assetId 供节点引用）。 */
export async function uploadCanvasImage(
  file: Blob,
  name?: string,
  width?: number,
  height?: number,
): Promise<{ assetId: string; id: string; mimeType?: string | null; sourceKind: string }> {
  const form = new FormData();
  form.append('file', file);
  if (name) form.append('name', name);
  if (width) form.append('width', String(width));
  if (height) form.append('height', String(height));
  const response = await authFetch('/api/nova/canvas/images', { method: 'POST', body: form });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as { assetId: string; id: string; mimeType?: string | null; sourceKind: string };
}

/** 画布图片访问（assets 鉴权链路）。 */
export function canvasImageUrl(assetId: string): string {
  return `/api/nova/assets/${encodeURIComponent(assetId)}/file`;
}
