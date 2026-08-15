import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createGifJob,
  deleteHistory,
  getGifJob,
  getReverseDraft,
  listHistories,
  listReverseRecords,
  patchGifJob,
  saveReverseDraft,
  saveReverseRecord,
  uploadGifResult,
  type GifJobPatch,
  type HistoryRow,
} from '@/lib/histories-api';
import { authFetch } from '@/lib/auth';

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

function row(overrides: Partial<HistoryRow> = {}): HistoryRow {
  return {
    id: 'h-1',
    type: 'reverse',
    status: 'completed',
    title: '一只猫',
    payload: { text: '一只猫' },
    imageIds: [],
    taskId: null,
    error: null,
    createdAt: '2026-08-08T08:00:00Z',
    updatedAt: '2026-08-08T08:00:00Z',
    ...overrides,
  };
}

describe('histories-api（WIN-41 T12/T13 统一历史云端客户端）', () => {
  beforeEach(() => {
    mockedAuthFetch.mockReset();
  });

  it('listHistories 分页参数 + 返回解析', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, {
      items: [row()],
      nextBefore: '2026-08-08T07:00:00Z',
    }));
    const page = await listHistories('reverse', { before: '2026-08-08T09:00:00Z', limit: 20 });
    expect(mockedAuthFetch).toHaveBeenCalledTimes(1);
    const [url] = mockedAuthFetch.mock.calls[0] as [string];
    expect(url).toBe('/api/nova/histories?type=reverse&before=2026-08-08T09%3A00%3A00Z&limit=20');
    expect(page.items).toHaveLength(1);
    expect(page.nextBefore).toBe('2026-08-08T07:00:00Z');
  });

  it('deleteHistory DELETE /api/nova/histories/{id}', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { ok: true }));
    await deleteHistory('h-1');
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/histories/h-1');
    expect(init.method).toBe('DELETE');
  });

  it('saveReverseRecord POST /api/nova/reverse/records', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, row()));
    const saved = await saveReverseRecord({ text: '一只猫', model: 'gemini-2.5-flash', mode: 'simple' });
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/reverse/records');
    expect(JSON.parse(String(init.body))).toEqual({ text: '一只猫', model: 'gemini-2.5-flash', mode: 'simple' });
    expect(saved.id).toBe('h-1');
  });

  it('listReverseRecords GET 双槽（limit=2）', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { items: [row(), row({ id: 'h-2' })] }));
    const items = await listReverseRecords(2);
    expect(mockedAuthFetch.mock.calls[0][0]).toBe('/api/nova/reverse/records?limit=2');
    expect(items).toHaveLength(2);
  });

  it('saveReverseDraft PUT（imageIds 引用）', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, row({ status: 'draft', imageIds: ['a1'] })));
    await saveReverseDraft({ text: '草稿', imageIds: ['a1'], model: 'm1', mode: 'simple' });
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/reverse/draft');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(String(init.body))).toEqual({ text: '草稿', imageIds: ['a1'], model: 'm1', mode: 'simple' });
  });

  it('getReverseDraft 空态返回 null', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { draft: null }));
    expect(await getReverseDraft()).toBeNull();
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { draft: row({ status: 'draft' }) }));
    expect((await getReverseDraft())?.status).toBe('draft');
  });

  it('createGifJob POST /api/nova/gif/jobs（payload 参数）', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, row({ type: 'gif', status: 'idle' })));
    await createGifJob({ prompt: '眨眼', model: 'gpt-image-2', loop: true, refImageAssetIds: ['a1'] });
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/gif/jobs');
    const body = JSON.parse(String(init.body));
    expect(body.prompt).toBe('眨眼');
    expect(body.refImageAssetIds).toEqual(['a1']);
  });

  it('patchGifJob PATCH 状态变迁（taskId/gridImageAssetId/error）', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, row({ type: 'gif', status: 'review_grid' })));
    const patch: GifJobPatch = { status: 'review_grid', taskId: 't-1', gridImageAssetId: 'g1' };
    const updated = await patchGifJob('h-1', patch);
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/gif/jobs/h-1');
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(String(init.body))).toEqual(patch);
    expect(updated.status).toBe('review_grid');
  });

  it('getGifJob GET 状态查询（中断恢复）', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, row({ type: 'gif', status: 'generating_grid' })));
    const job = await getGifJob('h-1');
    expect(mockedAuthFetch.mock.calls[0][0]).toBe('/api/nova/gif/jobs/h-1');
    expect(job.status).toBe('generating_grid');
  });

  it('uploadGifResult multipart 上传成品', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, row({ type: 'gif', status: 'done', imageIds: ['g1'] })));
    const done = await uploadGifResult('h-1', new Blob([new Uint8Array([1, 2])], { type: 'image/gif' }));
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/gif/jobs/h-1/result');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    expect(done.status).toBe('done');
  });
});
