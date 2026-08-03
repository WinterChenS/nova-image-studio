'use client';

/**
 * 认证层（M2 T2.5）——JWT token 管理 + 登录/注册 API + 带鉴权的 fetch 包装。
 *
 * - token 存 localStorage（`nova-auth-token`），配合 `authFetch` 自动注入
 *   `Authorization: Bearer <token>`；
 * - `register/login/logout/getMe` 对接 /api/auth/*；
 * - 登录态变化通过 `nova-auth-changed` CustomEvent 广播，供
 *   WorkspaceShell / SettingsModal / 数据层刷新订阅。
 */

const TOKEN_KEY = 'nova-auth-token';
const AUTH_CHANGED_EVENT = 'nova-auth-changed';

export interface AuthUser {
  id: string;
  username: string;
  role: string;
}

let cachedUser: AuthUser | null | undefined; // undefined = 未请求过

export function getToken(): string | null {
  if (typeof window === 'undefined') return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  if (typeof window === 'undefined') return;
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  if (typeof window === 'undefined') return;
  window.localStorage.removeItem(TOKEN_KEY);
}

export function isLoggedIn(): boolean {
  return Boolean(getToken());
}

/** 带鉴权的 fetch：已登录时自动附加 Bearer token。 */
export async function authFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const token = getToken();
  const headers = new Headers(init.headers || {});
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  return fetch(input, { ...init, headers });
}

/** 解析统一错误体 {error, code}；非 JSON 退化为状态文本。 */
export async function readApiError(response: Response): Promise<Error> {
  let message = `${response.status} ${response.statusText}`;
  try {
    const data: unknown = await response.json();
    if (data && typeof data === 'object' && 'error' in data) {
      const err = (data as { error?: unknown }).error;
      if (typeof err === 'string' && err) message = err;
    }
  } catch {
    // 非 JSON 响应，保留状态文本
  }
  return new Error(message);
}

export async function register(username: string, password: string): Promise<AuthUser> {
  const response = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as AuthUser;
  return data;
}

export async function login(username: string, password: string): Promise<AuthUser> {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { token: string; user: AuthUser };
  setToken(data.token);
  cachedUser = data.user;
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
  return data.user;
}

export function logout(): void {
  clearToken();
  cachedUser = null;
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

/** 拉取 /api/auth/me；token 失效时清除并返回 null。 */
export async function getMe(): Promise<AuthUser | null> {
  if (!isLoggedIn()) {
    cachedUser = null;
    return null;
  }
  try {
    const response = await authFetch('/api/auth/me', { cache: 'no-store' });
    if (response.status === 401) {
      clearToken();
      cachedUser = null;
      window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
      return null;
    }
    if (!response.ok) {
      cachedUser = null;
      return null;
    }
    cachedUser = (await response.json()) as AuthUser;
    return cachedUser;
  } catch {
    cachedUser = null;
    return null;
  }
}

/** 同步读取缓存的用户（可能为 null/undefined=未请求）。 */
export function getCachedUser(): AuthUser | null | undefined {
  return cachedUser;
}

/** 订阅登录态变化，返回取消函数。 */
export function onAuthChange(listener: () => void): () => void {
  window.addEventListener(AUTH_CHANGED_EVENT, listener);
  return () => window.removeEventListener(AUTH_CHANGED_EVENT, listener);
}

/** 供测试/重置使用。 */
export function resetAuthState(): void {
  cachedUser = undefined;
}
