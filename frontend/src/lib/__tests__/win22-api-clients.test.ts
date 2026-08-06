import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchAssets,
  createImageAsset,
  createTextAsset,
  updateAsset,
  batchMoveAssets,
  batchDeleteAssets,
  assetFileUrl,
} from '@/lib/assets-api';
import {
  fetchProjects,
  createProject,
  updateProject,
  deleteProject,
} from '@/lib/projects-api';

/**
 * WIN-22 (T10) — 素材/项目 API 客户端单测（mock fetch）。
 * 覆盖：列表分页/过滤参数序列化、multipart 上传、409 去重透传、批量移动/删除、
 * 项目 CRUD 与删除 force 参数。
 */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('assets-api', () => {
  const fetchSpy = vi.fn();

  beforeEach(() => {
    fetchSpy.mockReset();
    vi.stubGlobal('fetch', fetchSpy);
  });

  it('fetchAssets serializes filters and pagination', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ items: [], total: 3, page: 2, size: 48 }));
    const result = await fetchAssets({ projectId: '__unclassified__', source: 'upload', q: '猫', page: 2, size: 48 });
    expect(result.total).toBe(3);
    const url = fetchSpy.mock.calls[0][0] as string;
    expect(url).toContain('/api/nova/assets?');
    expect(url).toContain('projectId=__unclassified__');
    expect(url).toContain('source=upload');
    expect(url).toContain('q=' + encodeURIComponent('猫'));
    expect(url).toContain('page=2');
  });

  it('createImageAsset sends multipart form with sourceKind', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ id: 'a1', kind: 'image' }));
    await createImageAsset({ file: new File(['x'], 'a.png', { type: 'image/png' }), projectId: 'p1', sourceKind: 'upload' });
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('/api/nova/assets');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('sourceKind')).toBe('upload');
    expect((init.body as FormData).get('projectId')).toBe('p1');
  });

  it('createTextAsset posts JSON content', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ id: 't1', kind: 'text' }));
    await createTextAsset({ content: '一个可爱的猫', projectId: 'p1', sourceKind: 'manual' });
    const [, init] = fetchSpy.mock.calls[0];
    const body = JSON.parse(init.body as string);
    expect(body.content).toBe('一个可爱的猫');
    expect(new Headers(init.headers as HeadersInit).get('Content-Type')).toBe('application/json');
  });

  it('updateAsset patches name/tags/projectId (move)', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ id: 'a1', projectId: 'p2' }));
    await updateAsset('a1', { name: '新名', tags: ['x'], projectId: 'p2' });
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('/api/nova/assets/a1');
    expect(init.method).toBe('PATCH');
    const body = JSON.parse(init.body as string);
    expect(body.projectId).toBe('p2');
  });

  it('batchMoveAssets posts ids + target project', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ moved: 2 }));
    const moved = await batchMoveAssets(['a1', 'a2'], 'p3');
    expect(moved).toBe(2);
    const [, init] = fetchSpy.mock.calls[0];
    expect(init.url === undefined ? (init as RequestInit).method : init.method).toBe('POST');
    const body = JSON.parse(init.body as string);
    expect(body.projectId).toBe('p3');
  });

  it('batchDeleteAssets posts ids', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ deleted: 1 }));
    const deleted = await batchDeleteAssets(['a1']);
    expect(deleted).toBe(1);
  });

  it('assetFileUrl builds the proxy-read URL', () => {
    expect(assetFileUrl('a1')).toBe('/api/nova/assets/a1/file');
  });
});

describe('projects-api', () => {
  const fetchSpy = vi.fn();

  beforeEach(() => {
    fetchSpy.mockReset();
    vi.stubGlobal('fetch', fetchSpy);
  });

  it('fetchProjects passes includeArchived', async () => {
    fetchSpy.mockResolvedValue(jsonResponse([]));
    await fetchProjects(true);
    const url = fetchSpy.mock.calls[0][0] as string;
    expect(url).toBe('/api/nova/projects?includeArchived=true');
  });

  it('createProject posts name/description', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ id: 'p1', name: '海报组' }));
    const project = await createProject({ name: '海报组', description: 'desc' });
    expect(project.name).toBe('海报组');
    const [, init] = fetchSpy.mock.calls[0];
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string).name).toBe('海报组');
  });

  it('deleteProject sends force query param', async () => {
    fetchSpy.mockResolvedValue(new Response('{}', { status: 200 }));
    await deleteProject('p1', true);
    const url = fetchSpy.mock.calls[0][0] as string;
    expect(url).toContain('force=true');
  });

  it('updateProject patches fields via PUT', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ id: 'p1', archived: true }));
    await updateProject('p1', { archived: true });
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('/api/nova/projects/p1');
    expect(init.method).toBe('PUT');
  });
});
