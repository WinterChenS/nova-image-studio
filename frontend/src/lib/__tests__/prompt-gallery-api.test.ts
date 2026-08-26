import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchAllPromptSources,
  fetchPromptGalleryItem,
  searchPromptGallery,
  triggerGallerySync,
} from '@/lib/prompt-gallery-api';
import { authFetch } from '@/lib/auth';

/**
 * QA-P1 缺陷1 销项 — 广场读库 API 鉴权契约（方案 B）：
 * 读库端点受 anyRequest().authenticated() 门禁，前端必须经 authFetch 注入 Bearer；
 * 裸 fetch 在真实部署下必 401。本测试锁定「读库调用必须走 authFetch」的客户端契约。
 */

vi.mock('@/lib/auth', async importOriginal => {
  const actual = await importOriginal<typeof import('@/lib/auth')>();
  return { ...actual, authFetch: vi.fn(), getAuthHeaders: vi.fn(() => ({})) };
});

const mockedAuthFetch = vi.mocked(authFetch);

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

describe('prompt-gallery-api（QA-P1：读库必须走 authFetch 注入 Bearer）', () => {
  beforeEach(() => {
    mockedAuthFetch.mockReset();
  });

  it('searchPromptGallery 经 authFetch 携带鉴权请求并组装 source/category/q/tag/page 参数', async () => {
    mockedAuthFetch.mockResolvedValue(
      jsonResponse(200, { items: [item()], total: 1, page: 1, pageSize: 20, categories: ['海报'], sources: ['nanobanana'] }),
    );
    const result = await searchPromptGallery({ source: 'nanobanana', category: '海报', q: 'prompt', tag: '风格', page: 2, limit: 50 });
    expect(result.total).toBe(1);
    expect(result.items[0].uniqueKey).toBe('nanobanana-sec1-p1-0-0');
    expect(mockedAuthFetch).toHaveBeenCalledTimes(1);
    const [url] = mockedAuthFetch.mock.calls[0];
    expect(String(url)).toContain('source=nanobanana');
    expect(String(url)).toContain('category=' + encodeURIComponent('海报'));
    expect(String(url)).toContain('q=prompt');
    expect(String(url)).toContain('tag=' + encodeURIComponent('风格'));
    expect(String(url)).toContain('page=2');
    expect(String(url)).toContain('limit=50');
  });

  it('fetchPromptGalleryItem 经 authFetch 拉取详情', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, item({ id: 'x1', uniqueKey: 'x1' })));
    const result = await fetchPromptGalleryItem('x1');
    expect(result.uniqueKey).toBe('x1');
    expect(mockedAuthFetch).toHaveBeenCalledWith(
      '/api/nova/prompt-gallery/items/x1',
      expect.objectContaining({ cache: 'no-store' }),
    );
  });

  it('fetchAllPromptSources 分页拉全量并生成分类列表（含「全部」前置）', async () => {
    const page1 = [item({ id: 'a', uniqueKey: 'a', category: '海报' })];
    const page2 = [item({ id: 'b', uniqueKey: 'b', category: '角色' })];
    mockedAuthFetch
      .mockResolvedValueOnce(jsonResponse(200, { items: page1, total: 2, page: 1, pageSize: 100, categories: ['海报'], sources: [] }))
      .mockResolvedValueOnce(jsonResponse(200, { items: page2, total: 2, page: 2, pageSize: 100, categories: ['角色'], sources: [] }));
    const result = await fetchAllPromptSources();
    expect(result.prompts).toHaveLength(2);
    expect(result.categories[0]).toBe('全部');
    expect(result.categories).toContain('海报');
    expect(result.categories).toContain('角色');
    expect(mockedAuthFetch).toHaveBeenCalledTimes(2);
  });

  it('triggerGallerySync / fetchGallerySyncStatus 均走 authFetch（管理端点）', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { status: 'succeeded', totalUpserted: 42 }));
    const result = await triggerGallerySync();
    expect(result.status).toBe('succeeded');
    expect(result.totalUpserted).toBe(42);
    expect(mockedAuthFetch.mock.calls[0][0]).toBe('/api/nova/admin/prompt-gallery/sync');
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { status: 'idle' }));
    await fetchAllPromptSourcesSafeStatusProbe();
    function fetchAllPromptSourcesSafeStatusProbe(): Promise<unknown> {
      // 独立探测 sync/status 客户端（避免顶部循环依赖导入顺序问题）
      return import('@/lib/prompt-gallery-api').then(m => m.fetchGallerySyncStatus());
    }
    expect(mockedAuthFetch.mock.calls[1][0]).toBe('/api/nova/admin/prompt-gallery/sync/status');
  });
});
