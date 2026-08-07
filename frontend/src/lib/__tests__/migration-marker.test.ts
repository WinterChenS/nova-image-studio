import { beforeEach, describe, expect, it, vi } from 'vitest';
import 'fake-indexeddb/auto';

vi.mock('@/lib/auth', () => ({
  isLoggedIn: () => true,
  authFetch: vi.fn(),
  readApiError: vi.fn(),
}));

import { isFeatureMigrated, runAgentMigration, runCanvasMigration } from '@/lib/migration';

/**
 * WIN-40 G1-2 — 迁移「读取异常」与「无存量」区分：
 * 读取失败不得写迁移标记（避免阶段3 清理误删未迁移数据），仅「确实无存量」才标记。
 * 异常路径用真实 VersionError 复现（DB 实际版本高于代码打开版本，即 QA 评审 S1 指出的场景）。
 */
describe('migration marker semantics (G1-2)', () => {
  beforeEach(async () => {
    localStorage.clear();
    await Promise.all(['nova-agent-db', 'nova-image'].map(name => new Promise<void>(resolve => {
      const req = indexedDB.deleteDatabase(name);
      req.onsuccess = () => resolve();
      req.onerror = () => resolve();
      req.onblocked = () => resolve();
    })));
  });

  it('agent read failure (VersionError) does not mark migrated and rethrows', async () => {
    // 实际 DB 版本 2 > 代码打开版本 1 → openDB 抛 VersionError（真实读取异常场景）
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open('nova-agent-db', 2);
      req.onupgradeneeded = () => { req.result.createObjectStore('messages'); };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    db.close();

    await expect(runAgentMigration()).rejects.toThrow('读取本地会话失败');
    expect(isFeatureMigrated('agent')).toBe(false);
  });

  it('marks migrated only when genuinely no local canvas data', async () => {
    const count = await runCanvasMigration();
    expect(count).toBe(0);
    expect(isFeatureMigrated('canvas')).toBe(true);
  });
});
