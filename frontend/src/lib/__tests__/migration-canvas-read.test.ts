import { beforeEach, describe, expect, it } from 'vitest';
import 'fake-indexeddb/auto';
import { hasLocalCanvasData, readLocalCanvasData, readLocalCanvasImages } from '@/lib/migration';

/**
 * WIN-40 S1 修复回归 — 画布存量迁移读取：
 * 复现 localforage 1.10.0 的真实写入形态（DB version 2、persist 值为 JSON 字符串、
 * 图片为裸 Blob），验证迁移检测/读取不再静默失效（AC-5 画布项）。
 */
describe('migration canvas local read (S1 regression)', () => {
  beforeEach(async () => {
    indexedDB.deleteDatabase('nova-image');
  });

  async function seedLocalforageShape(projects: unknown[], blobs: Array<{ key: string; blob: Blob }>) {
    // 模拟 localforage 1.10.0：nova-image DB 建为 version 2（含 detect-blob-support store）
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open('nova-image', 2);
      req.onupgradeneeded = () => {
        const d = req.result;
        if (!d.objectStoreNames.contains('local-forage-detect-blob-support')) {
          d.createObjectStore('local-forage-detect-blob-support');
        }
        if (!d.objectStoreNames.contains('canvas_app_state')) {
          d.createObjectStore('canvas_app_state');
        }
        if (!d.objectStoreNames.contains('canvas_image_files')) {
          d.createObjectStore('canvas_image_files');
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    // persist 写入形态：JSON.stringify({state, version}) 字符串
    const tx = db.transaction(['canvas_app_state', 'canvas_image_files'], 'readwrite');
    tx.objectStore('canvas_app_state').put(
      JSON.stringify({ state: { projects }, version: 0 }),
      'nova-image:canvas_store',
    );
    for (const { key, blob } of blobs) {
      tx.objectStore('canvas_image_files').put(blob, key);
    }
    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  }

  it('detects local canvas data stored as JSON string in v2 DB', async () => {
    await seedLocalforageShape(
      [{ id: 'p1', title: '旧画布', nodes: [], connections: [], viewport: { x: 0, y: 0, k: 1 } }],
      [],
    );
    expect(await hasLocalCanvasData()).toBe(true);
    const data = await readLocalCanvasData();
    expect(data).not.toBeNull();
    expect(data!.projects[0].id).toBe('p1');
  });

  it('returns null when no canvas data', async () => {
    await seedLocalforageShape([], []);
    expect(await hasLocalCanvasData()).toBe(false);
    expect(await readLocalCanvasData()).toBeNull();
  });

  it('reads bare-Blob canvas images keyed by storageKey', async () => {
    // jsdom 的 Blob 经 Node structuredClone 会丢失（测试环境怪癖）；用 Node Blob 种子验证
    // 裸值按 key 关联读取（真实浏览器 IDB 原生保留 Blob）
    const nodeBlob = new (await import('node:buffer')).Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' });
    await seedLocalforageShape(
      [{
        id: 'p1',
        title: '旧画布',
        nodes: [{ id: 'n1', metadata: { storageKey: 'image:abc' } }],
        connections: [],
        viewport: { x: 0, y: 0, k: 1 },
      }],
      [{ key: 'image:abc', blob: nodeBlob as unknown as Blob }],
    );
    const images = await readLocalCanvasImages();
    expect(images.size).toBe(1);
    expect(images.has('image:abc')).toBe(true);
    expect(images.get('image:abc')!.size).toBe(3);
  });
});
