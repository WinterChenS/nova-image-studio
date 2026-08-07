'use client';

/**
 * WIN-25 (T18) — 全局模型目录 API 客户端：{@code GET /api/nova/models}（登录后
 * 只读，目录模型 + available 标记，A2/A5）。用户级模型 CRUD 已下线（Q1 直接移除），
 * 目录管理归 admin（T20 模型目录 Tab）。
 */

import { authFetch, readApiError } from '@/lib/auth';

export type CatalogModelType = 'image' | 'text';

export interface CatalogModel {
  id: string;
  type: CatalogModelType;
  protocol: string;
  name: string;
  modelId: string;
  baseUrl: string;
  enabled: boolean;
  /** A5: enabled=false 或 无可用账号 → false（前端禁用不可选） */
  available: boolean;
  // image-only
  builtinPreset?: string;
  maxRefImages?: number;
  maxOutputSize?: string;
  supportsAdvancedParams?: boolean;
  // text-only
  note?: string;
}

export interface CatalogView {
  imageModels: CatalogModel[];
  textModels: CatalogModel[];
}

/** 拉取全局模型目录（登录态）。失败抛错，由调用方决定降级策略。 */
export async function fetchCatalog(): Promise<CatalogModel[]> {
  const response = await authFetch('/api/nova/models', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as CatalogModel[];
  return Array.isArray(data) ? data : [];
}

export function splitCatalog(models: CatalogModel[]): CatalogView {
  return {
    imageModels: models.filter((m) => m.type === 'image'),
    textModels: models.filter((m) => m.type === 'text'),
  };
}
