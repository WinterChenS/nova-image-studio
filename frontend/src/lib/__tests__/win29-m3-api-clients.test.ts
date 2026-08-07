import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchMyUsage,
  fetchRoles,
  fetchPermissions,
  fetchRolePermissions,
  saveRolePermissions,
  type MyUsageResult,
  type RbacRole,
  type RbacPermission,
} from '@/lib/admin-api';

/**
 * WIN-29 (T24/T28) — M3 API 客户端单测（mock fetch）：
 * 我的用量（仅本人，无 userId 参数——A11）、角色×权限矩阵读/写
 * （PUT 保存矩阵 + 返回 before/after）。
 */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('fetchMyUsage (T24 / A11)', () => {
  const fetchSpy = vi.fn();

  beforeEach(() => {
    fetchSpy.mockReset();
    vi.stubGlobal('fetch', fetchSpy);
  });

  it('requests /api/nova/usage/me with pagination and never carries a userId', async () => {
    const payload: MyUsageResult = {
      items: [], total: 0, page: 1, size: 20,
      summary: { requestCount: 0, successCount: 0, successRate: null, totalCost: 0, totalTokens: 0 },
      daily: [],
    };
    fetchSpy.mockResolvedValue(jsonResponse(payload));
    const result = await fetchMyUsage({ page: 2, size: 20 });
    expect(result.total).toBe(0);
    const url = fetchSpy.mock.calls[0][0] as string;
    expect(url).toContain('/api/nova/usage/me?');
    expect(url).toContain('page=2');
    expect(url).toContain('size=20');
    expect(url).not.toContain('userId='); // A11: 无跨用户读取参数
  });

  it('serializes from/to filters when provided', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ items: [], total: 0, page: 1, size: 20, summary: {}, daily: [] }));
    await fetchMyUsage({ from: '2026-08-01T00:00:00Z', to: '2026-08-07T00:00:00Z' });
    const url = fetchSpy.mock.calls[0][0] as string;
    expect(url).toContain('from=' + encodeURIComponent('2026-08-01T00:00:00Z'));
    expect(url).toContain('to=' + encodeURIComponent('2026-08-07T00:00:00Z'));
  });
});

describe('RBAC admin API (T28)', () => {
  const fetchSpy = vi.fn();

  beforeEach(() => {
    fetchSpy.mockReset();
    vi.stubGlobal('fetch', fetchSpy);
  });

  const role: RbacRole = { id: 'r1', code: 'admin', name: '管理员', builtin: true };
  const perm: RbacPermission = { id: 'p1', code: 'workbench.view', type: 'menu', parentCode: null, label: '生图工作台', apiPath: null, sortOrder: 10 };

  it('fetchRoles hits /api/nova/admin/roles', async () => {
    fetchSpy.mockResolvedValue(jsonResponse([role]));
    const roles = await fetchRoles();
    expect(roles).toEqual([role]);
    expect(fetchSpy.mock.calls[0][0]).toBe('/api/nova/admin/roles');
  });

  it('fetchPermissions hits the permission-code list endpoint', async () => {
    fetchSpy.mockResolvedValue(jsonResponse([perm]));
    const perms = await fetchPermissions();
    expect(perms).toEqual([perm]);
    expect(fetchSpy.mock.calls[0][0]).toBe('/api/nova/admin/roles/permissions');
  });

  it('fetchRolePermissions returns permission ids for a role', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ roleId: 'r1', roleCode: 'admin', permissionIds: ['p1', 'p2'] }));
    const result = await fetchRolePermissions('r1');
    expect(result.permissionIds).toEqual(['p1', 'p2']);
    expect(fetchSpy.mock.calls[0][0]).toBe('/api/nova/admin/roles/r1/permissions');
  });

  it('saveRolePermissions PUTs the matrix and returns before/after', async () => {
    fetchSpy.mockResolvedValue(jsonResponse({ ok: true, roleId: 'r1', before: ['a'], after: ['a', 'b'] }));
    const result = await saveRolePermissions('r1', ['p1', 'p2']);
    expect(result.ok).toBe(true);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('/api/nova/admin/roles/r1/permissions');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ permissionIds: ['p1', 'p2'] });
  });
});
