import { beforeEach, describe, expect, it, vi } from 'vitest';
import 'fake-indexeddb/auto';

const authFetchMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/auth', () => ({
  isLoggedIn: () => true,
  authFetch: authFetchMock,
  readApiError: vi.fn(async (r: { status: number }) => new Error(`HTTP ${r.status}`)),
  getCachedUser: () => ({ id: 'user-42', username: 'alice', role: 'user' }),
  getMe: vi.fn(async () => ({ id: 'user-42', username: 'alice', role: 'user' })),
}));

import { isFeatureMigrated, runAgentMigration } from '@/lib/migration';

/** 种子 nova-agent-db：一条文本消息（无图片），模拟「仅文本聊天」老用户。 */
async function seedAgentMessagesDb(): Promise<void> {
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const req = indexedDB.open('nova-agent-db', 1);
    req.onupgradeneeded = () => {
      req.result.createObjectStore('messages', { keyPath: 'id' });
      req.result.createObjectStore('images', { keyPath: 'imgId' });
      req.result.createObjectStore('meta');
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction('messages', 'readwrite');
    tx.objectStore('messages').add({ id: 'm1', role: 'user', text: '你好', createdAt: 1700000000000 });
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}

function importCallBody() {
  const call = authFetchMock.mock.calls.find(c => String(c[0]).includes('/agent/import'));
  expect(call, '应发起 /agent/import 请求').toBeTruthy();
  const init = (call as unknown[])[1] as { body: string };
  return JSON.parse(init.body) as { conversations: Array<{ id: string }> };
}

describe('WIN-44 BUG-2 — Agent 迁移在无 nova-image-db（从未生成图片）时失败', () => {
  beforeEach(async () => {
    localStorage.clear();
    await Promise.all(['nova-agent-db', 'nova-image-db'].map(name => new Promise<void>(resolve => {
      const req = indexedDB.deleteDatabase(name);
      req.onsuccess = () => resolve();
      req.onerror = () => resolve();
      req.onblocked = () => resolve();
    })));
    authFetchMock.mockReset();
  });

  it('全新浏览器（仅 nova-agent-db 文本消息，无 nova-image-db）→ 迁移成功并写标记', async () => {
    await seedAgentMessagesDb();
    authFetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({ created: 1, skipped: 0, failed: 0 }) });

    const count = await runAgentMigration();

    expect(count).toBe(1);
    expect(isFeatureMigrated('agent')).toBe(true);
    expect(importCallBody().conversations).toHaveLength(1);
  });
});

describe('WIN-44 BUG-3 — 迁移会话 id 硬编码 local-agent-session + failed 被忽略', () => {
  beforeEach(async () => {
    localStorage.clear();
    await Promise.all(['nova-agent-db', 'nova-image-db'].map(name => new Promise<void>(resolve => {
      const req = indexedDB.deleteDatabase(name);
      req.onsuccess = () => resolve();
      req.onerror = () => resolve();
      req.onblocked = () => resolve();
    })));
    authFetchMock.mockReset();
  });

  it('会话 id 每用户唯一（local-agent-session-<userId>），不再硬编码', async () => {
    await seedAgentMessagesDb();
    authFetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({ created: 1, skipped: 0, failed: 0 }) });

    await runAgentMigration();

    expect(importCallBody().conversations[0].id).toBe('local-agent-session-user-42');
  });

  it('导入 failed>0 视为失败：不写迁移标记并抛错（不再静默标记已迁移）', async () => {
    await seedAgentMessagesDb();
    authFetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({ created: 0, skipped: 0, failed: 1 }) });

    await expect(runAgentMigration()).rejects.toThrow(/失败/);
    expect(isFeatureMigrated('agent')).toBe(false);
  });
});
