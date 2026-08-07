import { describe, expect, it } from 'vitest';
import { hasPerm } from '@/lib/permissions';
import type { AuthUser } from '@/lib/auth';

/**
 * WIN-30 (T23, A13) + WIN-36 (A1) — 前端权限码判定：以 /me 注入的
 * permissions 数组为唯一判定源（V6 回填 + 服务端恒注入后 admin 角色兜底
 * 已不可达，WIN-36 移除）；缺失数组（旧缓存）→ false；未登录 → false。
 */
describe('hasPerm', () => {
  const adminUser: AuthUser = {
    id: '1',
    username: 'boss',
    role: 'admin',
    roles: ['admin'],
    permissions: ['account.manage', 'audit.view', 'audit.export', 'model.catalog.manage', 'pricing.manage'],
  };
  const userUser: AuthUser = {
    id: '2',
    username: 'alice',
    role: 'user',
    roles: ['user'],
    permissions: ['workbench.view', 'usage.me'],
  };

  it('returns true when the permission code is present', () => {
    expect(hasPerm('account.manage', adminUser)).toBe(true);
    expect(hasPerm('workbench.view', userUser)).toBe(true);
  });

  it('returns false when the permission code is absent', () => {
    expect(hasPerm('audit.view', userUser)).toBe(false);
    expect(hasPerm('user.manage', userUser)).toBe(false);
  });

  it('returns false for anonymous users', () => {
    expect(hasPerm('workbench.view', null)).toBe(false);
    expect(hasPerm('workbench.view', undefined)).toBe(false);
  });

  it('admin without permissions field gets nothing (WIN-36 A1: no role fallback)', () => {
    const legacyAdmin: AuthUser = { id: '3', username: 'old-boss', role: 'admin' };
    expect(hasPerm('account.manage', legacyAdmin)).toBe(false);
  });

  it('user without permissions field gets nothing', () => {
    const legacyUser: AuthUser = { id: '4', username: 'old-user', role: 'user' };
    expect(hasPerm('account.manage', legacyUser)).toBe(false);
  });
});
