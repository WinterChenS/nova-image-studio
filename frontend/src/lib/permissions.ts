'use client';

/**
 * WIN-25 (T23, A13) — 前端权限码工具：`hasPerm(code)` 同步判定 +
 * `usePerm(code)` React Hook（登录态变化自动刷新）。权限码来自
 * `/api/auth/me` 的 `permissions: string[]`（后端 UserPermissionService，
 * ADR-29 缓存加载）；菜单/按钮级渲染以 `hasPerm` 为准，直调接口仍由后端
 * @PreAuthorize 兜底 403。
 */

import { useEffect, useState } from 'react';
import { getCachedUser, getMe, onAuthChange, type AuthUser } from '@/lib/auth';

/** 用户是否持有某权限码（无用户/未登录 → false）。 */
export function hasPerm(code: string, user?: AuthUser | null | undefined): boolean {
  if (!user) return false;
  if (Array.isArray(user.permissions)) {
    return user.permissions.includes(code);
  }
  // 兜底：旧响应无 permissions 字段时按 admin 角色放行（兼容降级）
  return user.role === 'admin';
}

/** 当前登录用户是否持有某权限码（读取内存缓存，同步）。 */
export function hasPermSync(code: string): boolean {
  return hasPerm(code, getCachedUser());
}

/** React Hook：订阅登录态变化，返回权限码判定 + 最新用户。 */
export function usePerm(code: string): boolean {
  const [user, setUser] = useState<AuthUser | null | undefined>(getCachedUser());

  useEffect(() => {
    // 挂载时刷新一次（确保 permissions 最新）
    void getMe().then(setUser);
    const unsubscribe = onAuthChange(() => void getMe().then(setUser));
    return unsubscribe;
  }, []);

  return hasPerm(code, user);
}

/** React Hook：返回当前登录用户（含 roles/permissions）。 */
export function useAuthUser(): AuthUser | null | undefined {
  const [user, setUser] = useState<AuthUser | null | undefined>(getCachedUser());

  useEffect(() => {
    void getMe().then(setUser);
    const unsubscribe = onAuthChange(() => void getMe().then(setUser));
    return unsubscribe;
  }, []);

  return user;
}
