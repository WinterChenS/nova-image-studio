import { beforeEach, describe, expect, it, vi } from 'vitest';
import 'fake-indexeddb/auto';

vi.mock('@/lib/auth', () => ({
  isLoggedIn: () => true,
  authFetch: vi.fn(),
  readApiError: vi.fn(),
  getCachedUser: () => ({ id: 'user-42', username: 'alice', role: 'user' }),
  getMe: vi.fn(async () => ({ id: 'user-42', username: 'alice', role: 'user' })),
}));

import {
  hasLegacyDataToClean,
  markFeatureMigrated,
  runLegacyCleanup,
} from '@/lib/migration';

/**
 * WIN-42 (T17, ADR-41/C10) — 本地存量清理：仅当对应功能迁移标记存在时执行；
 * 清理失败不破坏新数据（尽力而为）；无迁移标记时不清理。
 */
describe('WIN-42 T17 — 本地存量清理（按迁移标记）', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('无迁移标记时不清理任何数据', async () => {
    localStorage.setItem('nova-gif-active-job', '{"id":"job"}');
    const result = await runLegacyCleanup();
    expect(result.cleanedDatabases).toEqual([]);
    expect(result.clearedSettings).toEqual([]);
    expect(result.clearedGifJob).toBe(false);
    expect(localStorage.getItem('nova-gif-active-job')).not.toBeNull();
  });

  it('迁移标记存在时清除对应 legacy settings 与 gif job', async () => {
    markFeatureMigrated('gif');
    localStorage.setItem('nova-gif-active-job', '{"id":"job"}');
    localStorage.setItem('nova-t2i-settings', '{"x":1}');
    localStorage.setItem('theme', 'dark');   // UI 偏好保留
    const result = await runLegacyCleanup();
    expect(result.clearedGifJob).toBe(true);
    expect(result.clearedSettings).toContain('nova-t2i-settings');
    expect(result.clearedSettings).not.toContain('theme');
    expect(localStorage.getItem('nova-gif-active-job')).toBeNull();
    expect(localStorage.getItem('nova-t2i-settings')).toBeNull();
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('清理已迁移功能的 IndexedDB 数据库', async () => {
    markFeatureMigrated('agent');
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open('nova-agent-db', 1);
      req.onupgradeneeded = () => { req.result.createObjectStore('messages'); };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    db.close();
    const result = await runLegacyCleanup();
    expect(result.cleanedDatabases).toContain('nova-agent-db');
  });

  it('hasLegacyDataToClean 检测待清理存量', async () => {
    expect(hasLegacyDataToClean()).toBe(false);
    markFeatureMigrated('agent');
    expect(hasLegacyDataToClean()).toBe(true);   // 迁移标记存在即提示可清理
  });
});
