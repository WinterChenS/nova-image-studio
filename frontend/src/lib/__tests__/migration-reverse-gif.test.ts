import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  hasLocalGifData,
  isFeatureMigrated,
  runGifMigration,
  runReverseMigration,
} from '@/lib/migration';
import { authFetch, isLoggedIn } from '@/lib/auth';

vi.mock('@/lib/auth', async importOriginal => {
  const actual = await importOriginal<typeof import('@/lib/auth')>();
  return {
    ...actual,
    isLoggedIn: vi.fn(() => true),
    getCachedUser: vi.fn(() => ({ id: 'user-1' })),
    getMe: vi.fn(async () => ({ id: 'user-1' })),
    authFetch: vi.fn(),
    readApiError: vi.fn(async (r: Response) => new Error(`HTTP ${r.status}`)),
    getAuthHeaders: vi.fn(() => ({})),
  };
});

const mockedAuthFetch = vi.mocked(authFetch);
const mockedIsLoggedIn = vi.mocked(isLoggedIn);

// 动态 import 的依赖 mock
vi.mock('@/lib/assets-api', () => ({
  createImageAsset: vi.fn(async () => ({ id: 'asset-1' })),
}));

vi.mock('@/lib/reverse-prompt-store', () => ({
  readLocalReverseData: vi.fn(async () => ({
    current: { slot: 'current', text: '当前结果', model: 'm1', mode: 'simple', timestamp: 2 },
    previous: { slot: 'previous', text: '上次结果', model: 'm2', mode: 'expert', timestamp: 1 },
    draft: null,
  })),
  hasLocalReverseData: vi.fn(async () => true),
}));

vi.mock('@/lib/gif-job-store', () => ({
  loadActiveGifJob: vi.fn(() => ({
    id: 'job-1',
    status: 'done',
    prompt: '眨眼 GIF',
    loop: true,
    closedLoop: false,
    model: 'gpt-image-2',
    refImages: [],
    frameDelayMs: 120,
    loopCount: 0,
    framePadding: 1.5,
    createdAt: '2026-08-08T00:00:00Z',
    updatedAt: '2026-08-08T00:00:00Z',
  })),
}));

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('WIN-41 T14 迁移扩展（反推/GIF 存量 → 服务端导入，幂等）', () => {
  const localStorageBackup = globalThis.localStorage;

  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.localStorage = new (class {
      private map = new Map<string, string>();
      getItem(k: string): string | null { return this.map.get(k) ?? null; }
      setItem(k: string, v: string): void { this.map.set(k, v); }
      removeItem(k: string): void { this.map.delete(k); }
      clear(): void { this.map.clear(); }
      key(i: number): string | null { return [...this.map.keys()][i] ?? null; }
      get length(): number { return this.map.size; }
    })();
  });

  afterEach(() => {
    globalThis.localStorage = localStorageBackup;
  });

  it('hasLocalGifData 检测 localStorage 存量', () => {
    expect(hasLocalGifData()).toBe(false);
    globalThis.localStorage.setItem('nova-gif-active-job', JSON.stringify({ id: 'j1', status: 'idle' }));
    expect(hasLocalGifData()).toBe(true);
  });

  it('runReverseMigration 走 /migration/reverse/import（双槽 items + 稳定幂等 id）', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { created: 2, skipped: 0, failed: 0 }));
    const migrated = await runReverseMigration();
    expect(migrated).toBe(2);
    expect(mockedAuthFetch).toHaveBeenCalledTimes(1);
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/migration/reverse/import');
    expect(init.method).toBe('POST');
    const body = JSON.parse(String(init.body)) as { items: Array<Record<string, unknown>> };
    expect(body.items).toHaveLength(2);
    expect(body.items[0].id).toBe('local-reverse-previous-user-1');
    expect(body.items[1].id).toBe('local-reverse-current-user-1');
    expect(body.items[0]).toMatchObject({ status: 'completed', payload: { text: '上次结果' } });
    expect(isFeatureMigrated('reverse')).toBe(true);
  });

  it('runReverseMigration 导入失败（failed>0）不写迁移标记', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { created: 1, skipped: 0, failed: 1 }));
    await expect(runReverseMigration()).rejects.toThrow('导入失败 1 条');
    expect(isFeatureMigrated('reverse')).toBe(false);
  });

  it('runGifMigration 走 /migration/gif/import（done 快照 + 幂等 id）', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { created: 1, skipped: 0, failed: 0 }));
    const migrated = await runGifMigration();
    expect(migrated).toBe(1);
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/migration/gif/import');
    const body = JSON.parse(String(init.body)) as { items: Array<Record<string, unknown>> };
    expect(body.items).toHaveLength(1);
    expect(body.items[0]).toMatchObject({
      id: 'local-gif-job-user-1',
      status: 'done',
      payload: { prompt: '眨眼 GIF', loop: true, encodeMode: 'client' },
    });
    expect(isFeatureMigrated('gif')).toBe(true);
  });

  it('未登录时迁移抛错且不写标记', async () => {
    mockedIsLoggedIn.mockReturnValue(false);
    await expect(runReverseMigration()).rejects.toThrow('请先登录');
    expect(isFeatureMigrated('reverse')).toBe(false);
    mockedIsLoggedIn.mockReturnValue(true);
  });
});
