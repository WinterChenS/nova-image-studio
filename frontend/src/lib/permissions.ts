'use client';

/**
 * WIN-25 (T23, A13) + WIN-36 (A1) — 前端权限码工具：`hasPerm(code)` 同步判定 +
 * `usePerm(code)` React Hook（登录态变化自动刷新）。权限码来自 `/api/auth/me`
 * 的 `permissions: string[]`（后端 UserPermissionService，ADR-29 缓存加载；
 * V6 回填 + 服务端恒注入后 permissions 必存在，WIN-36 移除 admin 角色兜底，
 * 权限码体系为唯一判定）；菜单/按钮级渲染以 `hasPerm` 为准，直调接口仍由后端
 * @PreAuthorize 兜底 403。
 */

import { useEffect, useState } from 'react';
import { getCachedUser, getMe, onAuthChange, type AuthUser } from '@/lib/auth';

/** 用户是否持有某权限码（无用户/未登录 → false；缺失 permissions → false）。 */
export function hasPerm(code: string, user?: AuthUser | null | undefined): boolean {
  if (!user) return false;
  // WIN-36 (A1): /me 必含 permissions（V6 回填 + 服务端恒注入），admin 角色
  // 兜底已不可达且会绕过权限码体系 —— 移除；防御性处理缺失数组（旧缓存）→ false。
  return user.permissions?.includes(code) ?? false;
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
