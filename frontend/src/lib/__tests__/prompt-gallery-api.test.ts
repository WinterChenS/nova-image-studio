import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchAllPromptSources,
  fetchPromptGalleryItem,
  searchPromptGallery,
  triggerGallerySync,
} from '@/lib/prompt-gallery-api';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function item(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 'nanobanana-sec1-p1-0-0',
    uniqueKey: 'nanobanana-sec1-p1-0-0',
    source: 'nanobanana',
    sourceUrl: 'https://github.com/x/y',
    title: '海报示例',
    content: '一张海报',
    images: ['https://img.example/a.png'],
    tags: ['海报'],
    category: '海报',
    contributor: '作者',
    notes: '',
    syncedAt: '2026-08-16T00:00:00Z',
    ...overrides,
  };
}

describe('prompt-gallery-api（WIN-42 T15 读库客户端，无运行时远程 fetch）', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('searchPromptGallery 组装 source/category/q/tag/page 参数', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse(200, { items: [item()], total: 1, page: 1, pageSize: 20, categories: ['海报'], sources: ['nanobanana'] }),
    );
    const result = await searchPromptGallery({ source: 'nanobanana', category: '海报', q: 'prompt', tag: '风格', page: 2, limit: 50 });
    expect(result.total).toBe(1);
    expect(result.items[0].uniqueKey).toBe('nanobanana-sec1-p1-0-0');
    const url = String(fetchSpy.mock.calls[0][0]);
    expect(url).toContain('source=nanobanana');
    expect(url).toContain('category=' + encodeURIComponent('海报'));
    expect(url).toContain('q=prompt');
    expect(url).toContain('tag=' + encodeURIComponent('风格'));
    expect(url).toContain('page=2');
    expect(url).toContain('limit=50');
  });

  it('fetchPromptGalleryItem 拉取详情并返回服务端条目', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse(200, item({ id: 'x1', uniqueKey: 'x1' })));
    const result = await fetchPromptGalleryItem('x1');
    expect(result.uniqueKey).toBe('x1');
  });

  it('fetchAllPromptSources 分页拉全量并生成分类列表（含「全部」前置）', async () => {
    const page1 = [item({ id: 'a', uniqueKey: 'a', category: '海报' })];
    const page2 = [item({ id: 'b', uniqueKey: 'b', category: '角色' })];
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse(200, { items: page1, total: 2, page: 1, pageSize: 100, categories: ['海报'], sources: [] }))
      .mockResolvedValueOnce(jsonResponse(200, { items: page2, total: 2, page: 2, pageSize: 100, categories: ['角色'], sources: [] }));
    const result = await fetchAllPromptSources();
    expect(result.prompts).toHaveLength(2);
    expect(result.categories[0]).toBe('全部');
    expect(result.categories).toContain('海报');
    expect(result.categories).toContain('角色');
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it('triggerGallerySync 走 authFetch 触发服务端同步', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse(200, { status: 'succeeded', totalUpserted: 42 }));
    const result = await triggerGallerySync();
    expect(result.status).toBe('succeeded');
    expect(result.totalUpserted).toBe(42);
  });
});
