import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  hasLocalGifData,
  isFeatureMigrated,
  runGifMigration,
  runReverseMigration,
} from '@/lib/migration';
import { isLoggedIn } from '@/lib/auth';

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

const mockedIsLoggedIn = vi.mocked(isLoggedIn);

// 动态 import 的依赖 mock
vi.mock('@/lib/histories-api', () => ({
  saveReverseRecord: vi.fn(async () => ({ id: 'h-1' })),
  saveReverseDraft: vi.fn(async () => ({ id: 'h-2' })),
  createGifJob: vi.fn(async () => ({ id: 'g-1', status: 'idle' })),
  patchGifJob: vi.fn(async () => ({ id: 'g-1' })),
}));

vi.mock('@/lib/assets-api', () => ({
  createImageAsset: vi.fn(async () => ({ id: 'asset-1' })),
}));

vi.mock('@/lib/reverse-prompt-store', () => ({
  readLocalReverseData: vi.fn(async () => ({
    current: { slot: 'current', text: '当前结果', model: 'm1', mode: 'simple', timestamp: 1 },
    previous: { slot: 'previous', text: '上次结果', model: 'm2', mode: 'expert', timestamp: 2 },
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

describe('WIN-41 T14 迁移扩展（反推/GIF 存量 → 云端）', () => {
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

  it('runReverseMigration 导入双槽并写迁移标记', async () => {
    const { saveReverseRecord } = await import('@/lib/histories-api');
    const migrated = await runReverseMigration();
    expect(migrated).toBe(2);
    expect(saveReverseRecord).toHaveBeenCalledTimes(2);
    expect(saveReverseRecord).toHaveBeenNthCalledWith(1, expect.objectContaining({ text: '上次结果' }));
    expect(saveReverseRecord).toHaveBeenNthCalledWith(2, expect.objectContaining({ text: '当前结果' }));
    expect(isFeatureMigrated('reverse')).toBe(true);
  });

  it('runGifMigration 创建云端 job 并按 done 状态推进状态机', async () => {
    const { createGifJob, patchGifJob } = await import('@/lib/histories-api');
    const migrated = await runGifMigration();
    expect(migrated).toBe(1);
    expect(createGifJob).toHaveBeenCalledTimes(1);
    expect(createGifJob).toHaveBeenCalledWith(expect.objectContaining({ prompt: '眨眼 GIF', loop: true }));
    // done 需依次经历 generating_grid → review_grid → generating_gif → done
    const states = vi.mocked(patchGifJob).mock.calls.map(call => (call[1] as { status: string }).status);
    expect(states).toEqual(['generating_grid', 'review_grid', 'generating_gif', 'done']);
    expect(isFeatureMigrated('gif')).toBe(true);
  });

  it('未登录时迁移抛错且不写标记', async () => {
    mockedIsLoggedIn.mockReturnValue(false);
    await expect(runReverseMigration()).rejects.toThrow('请先登录');
    expect(isFeatureMigrated('reverse')).toBe(false);
    mockedIsLoggedIn.mockReturnValue(true);
  });
});
