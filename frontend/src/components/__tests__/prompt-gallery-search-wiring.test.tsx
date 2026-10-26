import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

/**
 * WIN-42 复审修复② — 广场搜索服务端化接线：
 * PromptGallery / PromptSelectDialog / 画布导入弹窗三处 UI 调用服务端 ILIKE/tag
 * 搜索 API（category/q 参数），不再全量拉取后客户端过滤。
 */

const searchPromptGalleryMock = vi.hoisted(() => vi.fn());

// 部分 mock：仅替换服务端检索函数，保留 toPromptWithKey 等真实导出
vi.mock('@/lib/prompt-gallery-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/prompt-gallery-api')>();
  return { ...actual, searchPromptGallery: searchPromptGalleryMock };
});

// 组件树内 IntersectionObserver / fetch 兜底（jsdom 缺省）
class IntersectionObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }
}

function galleryItem(id: string) {
  return {
    id,
    uniqueKey: id,
    source: 'nanobanana',
    sourceUrl: 'https://github.com/x/y',
    title: `提示词-${id}`,
    content: `${id} 的内容`,
    images: [],
    tags: ['风格'],
    category: '海报',
    contributor: '作者',
    notes: '',
    syncedAt: '2026-08-26T00:00:00Z',
  };
}

const FIND_OPTS = { timeout: 5000 } as const;
const WAIT_OPTS = { timeout: 5000 } as const;

describe('WIN-42 复审修复② — 提示广场服务端搜索接线', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    vi.stubGlobal('IntersectionObserver', IntersectionObserverStub);
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      json: async () => ({ keywords: [] }),
    })) as unknown);
    searchPromptGalleryMock.mockReset();
    searchPromptGalleryMock.mockResolvedValue({
      items: [galleryItem('a'), galleryItem('b')],
      total: 2,
      page: 1,
      pageSize: 20,
      categories: ['海报', '角色'],
      sources: ['nanobanana'],
    });
  });

  it('PromptGallery：初始加载调用服务端检索（page=1, limit=20，无 q/category）并渲染条目', async () => {
    const { PromptGallery } = await import('@/components/PromptGallery');
    render(<PromptGallery />);
    expect(await screen.findByText('提示词-a', {}, FIND_OPTS)).toBeInTheDocument();
    expect(searchPromptGalleryMock).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, limit: 20, q: undefined, category: undefined }),
    );
    expect(screen.getByText(/共 2 个提示词 · 已加载 2 个/)).toBeInTheDocument();
  });

  it('PromptGallery：搜索输入防抖后以 q 参数重新检索第 1 页', async () => {
    const { PromptGallery } = await import('@/components/PromptGallery');
    render(<PromptGallery />);
    await screen.findByText('提示词-a', {}, FIND_OPTS);
    fireEvent.change(screen.getByPlaceholderText('搜索提示词、标题或作者...'), { target: { value: '赛博朋克' } });
    // 越过 300ms 防抖窗口后断言以 q 参数重新请求第 1 页
    await waitFor(
      () => expect(searchPromptGalleryMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ q: '赛博朋克', page: 1 }),
      ),
      WAIT_OPTS,
    );
  });

  it('PromptGallery：点击分类徽标以 category 参数重新检索', async () => {
    const { PromptGallery } = await import('@/components/PromptGallery');
    render(<PromptGallery />);
    await screen.findByText('提示词-a', {}, FIND_OPTS);
    fireEvent.click(screen.getByText('角色'));
    await waitFor(
      () => expect(searchPromptGalleryMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ category: '角色', page: 1 }),
      ),
      WAIT_OPTS,
    );
  });

  it('PromptSelectDialog：打开即服务端检索，选中条目回传内容', async () => {
    const { PromptSelectDialog } = await import('@/components/PromptSelectDialog');
    const onSelect = vi.fn();
    render(<PromptSelectDialog open onOpenChange={() => {}} onSelect={onSelect} />);
    expect(await screen.findByText('提示词-b', {}, FIND_OPTS)).toBeInTheDocument();
    expect(searchPromptGalleryMock).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, limit: 12 }),
    );
    fireEvent.click(screen.getByText('提示词-b'));
    expect(onSelect).toHaveBeenCalledWith('b 的内容');
  });

  it('画布导入弹窗：打开即服务端检索，选中后确认导入', async () => {
    const { CanvasPromptGalleryImportDialog } = await import('@/components/canvas/components/canvas-prompt-gallery-import-dialog');
    const onConfirm = vi.fn();
    render(<CanvasPromptGalleryImportDialog open importing={false} onOpenChange={() => {}} onConfirm={onConfirm} />);
    expect(await screen.findByText('提示词-a', {}, FIND_OPTS)).toBeInTheDocument();
    expect(searchPromptGalleryMock).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, limit: 40, q: undefined, category: undefined }),
    );
    fireEvent.click(screen.getByText('提示词-a'));
    fireEvent.click(screen.getByRole('button', { name: /导入到画布/ }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm.mock.calls[0][0]).toMatchObject({ uniqueKey: 'a' });
  });
});
