'use client';

/**
 * WIN-42 (T15, ADR-40) — 提示广场读库 API 客户端（对接 /api/nova/prompt-gallery/*）。
 * 前端不再运行时 fetch 远程源（AC-9）；解析逻辑由服务端 PromptParserRegistry 承担，
 * 本模块只做读库 + 服务端搜索（T16 ILIKE/tag）。
 */

import { authFetch, readApiError } from '@/lib/auth';
import { ALL_CATEGORY, DEFAULT_CATEGORIES, type PromptWithKey } from '@/lib/prompt-gallery-data';

export interface ServerGalleryItem {
  id: string;
  uniqueKey: string;
  source: string;
  sourceUrl: string | null;
  title: string;
  content: string;
  images: string[];
  tags: string[];
  category: string | null;
  contributor: string | null;
  notes: string | null;
  syncedAt: string | null;
}

export interface GalleryPageResult {
  items: ServerGalleryItem[];
  total: number;
  page: number;
  pageSize: number;
  categories: string[];
  sources: string[];
}

/** 服务端条目 → 前端展示形状（uniqueKey 对齐旧数据流；null 归一化为空串/undefined）。 */
export function toPromptWithKey(item: ServerGalleryItem): PromptWithKey {
  return {
    id: item.id,
    uniqueKey: item.uniqueKey,
    source: item.source,
    sourceUrl: item.sourceUrl || undefined,
    title: item.title,
    content: item.content,
    images: item.images ?? [],
    tags: item.tags ?? [],
    contributor: item.contributor || '',
    notes: item.notes || '',
    category: item.category || undefined,
  };
}

/** 服务端读库搜索（T16：source/category 精确 + q ILIKE + tag 标签）。经 authFetch 注入 Bearer（QA-P1）。 */
export async function searchPromptGallery(params: {
  source?: string;
  category?: string;
  q?: string;
  tag?: string;
  page?: number;
  limit?: number;
} = {}): Promise<GalleryPageResult> {
  const search = new URLSearchParams();
  if (params.source) search.set('source', params.source);
  if (params.category) search.set('category', params.category);
  if (params.q) search.set('q', params.q);
  if (params.tag) search.set('tag', params.tag);
  if (params.page) search.set('page', String(params.page));
  if (params.limit) search.set('limit', String(params.limit));
  const query = search.toString();
  const response = await authFetch(`/api/nova/prompt-gallery/items${query ? `?${query}` : ''}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as GalleryPageResult;
}

export async function fetchPromptGalleryItem(id: string): Promise<ServerGalleryItem> {
  const response = await authFetch(`/api/nova/prompt-gallery/items/${encodeURIComponent(id)}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerGalleryItem;
}

/**
 * 兼容既有消费方的全量读库：分页拉取全部入库条目（服务端唯一数据源，无运行时远程 fetch）。
 * 返回形状与旧 fetchAllPromptSources 一致（{prompts, categories}）。经 authFetch（QA-P1）。
 */
export async function fetchAllPromptSources(): Promise<{ prompts: PromptWithKey[]; categories: string[] }> {
  const pageSize = 100;
  const prompts: PromptWithKey[] = [];
  const categorySet = new Set<string>(DEFAULT_CATEGORIES);
  let page = 1;
  let total = 1;
  while (prompts.length < total) {
    const result = await searchPromptGallery({ page, limit: pageSize });
    total = result.total;
    for (const item of result.items) {
      const prompt = toPromptWithKey(item);
      if (prompt.category) categorySet.add(prompt.category);
      prompts.push(prompt);
    }
    if (result.items.length === 0) break;
    page += 1;
  }
  return {
    prompts,
    categories: [ALL_CATEGORY, ...Array.from(categorySet).filter(c => c !== ALL_CATEGORY)],
  };
}

/** 手动刷新（管理端）：触发服务端同步（限频由服务端校验，A5）。 */
export async function triggerGallerySync(): Promise<{ status: string; totalUpserted: number }> {
  const response = await authFetch('/api/nova/admin/prompt-gallery/sync', { method: 'POST' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as { status: string; totalUpserted: number };
}

/** 同步状态查询（管理端，AC-12）。 */
export async function fetchGallerySyncStatus(): Promise<Record<string, unknown>> {
  const response = await authFetch('/api/nova/admin/prompt-gallery/sync/status', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as Record<string, unknown>;
}
