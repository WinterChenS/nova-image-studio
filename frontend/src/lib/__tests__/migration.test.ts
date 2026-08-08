import { describe, expect, it } from 'vitest';
import { collectCanvasStorageKeys, rewriteCanvasStorageKeys } from '@/lib/migration';

describe('migration canvas reference rewrite (WIN-40 T7)', () => {
  it('collects legacy image: storage keys from nested nodes', () => {
    const project = {
      id: 'p1',
      nodes: [
        { id: 'n1', metadata: { storageKey: 'image:abc' } },
        { id: 'n2', metadata: { storageKey: 'image:def', nested: { storageKey: 'image:ghi' } } },
        { id: 'n3', metadata: { storageKey: 'asset-id-1' } },   // 非本地引用不收集
      ],
    };
    const keys: string[] = [];
    collectCanvasStorageKeys(project, (k) => keys.push(k));
    expect(keys.sort()).toEqual(['image:abc', 'image:def', 'image:ghi']);
  });

  it('rewrites storageKey to assetId preserving structure', () => {
    const project = {
      id: 'p1',
      nodes: [
        { id: 'n1', metadata: { storageKey: 'image:abc' } },
        { id: 'n2', metadata: { storageKey: 'image:def' } },
      ],
    };
    const mapping = new Map([['image:abc', 'asset-1'], ['image:def', 'asset-2']]);
    rewriteCanvasStorageKeys(project, mapping);
    expect(project.nodes[0].metadata.storageKey).toBe('asset-1');
    expect(project.nodes[1].metadata.storageKey).toBe('asset-2');
  });

  it('leaves unmapped keys untouched', () => {
    const project = { nodes: [{ metadata: { storageKey: 'image:unknown' } }] };
    rewriteCanvasStorageKeys(project, new Map([['image:abc', 'asset-1']]));
    expect(project.nodes[0].metadata.storageKey).toBe('image:unknown');
  });
});
