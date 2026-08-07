'use client';

/**
 * WIN-25 (T20/T21) — 管理控制台 API 客户端：账号池（accounts + test）、
 * 模型目录（admin/models）、价格配置（pricing）、审计与费用（usage + export）。
 * 全部接口需 PERM_ 权限（后端 @PreAuthorize，403 兜底）。
 */

import { authFetch, readApiError } from '@/lib/auth';

export interface AdminAccount {
  id: string;
  name: string;
  protocol: string;
  baseUrl: string;
  apiKey: string; // 掩码
  modelScope: string[];
  status: 'active' | 'paused' | 'broken' | 'deleted';
  priority?: number;
  /** WIN-29 (T26): 月度费用上限（CNY）；达限自动 paused，管理员可解除。 */
  monthlyCapCost?: number | null;
  health: {
    consecutiveFailures?: number;
    cooldownUntil?: string | null;
    lastError?: string;
    lastSuccessAt?: string | null;
  };
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface AdminCatalogModel {
  id: string;
  type: 'image' | 'text';
  protocol: string;
  name: string;
  modelId: string;
  baseUrl: string;
  enabled: boolean;
  builtinPreset?: string;
  maxRefImages?: number;
  maxOutputSize?: string;
  supportsAdvancedParams?: boolean;
  note?: string;
}

export interface PricingRow {
  id: string;
  modelId: string;
  modelName: string;
  currency: string;
  perRequestPrice: number;
  pricePerToken: number;
}

export interface UsageItem {
  id: number;
  userId?: string | null;
  username?: string;
  modelId?: string | null;
  modelName?: string;
  accountId?: string | null;
  accountName?: string;
  protocol?: string;
  reqType?: string;
  refType?: string;
  status?: string;
  inputTokens?: number;
  outputTokens?: number;
  images?: number;
  cost?: number;
  currency?: string;
  durationMs?: number;
  createdAt?: string;
}

export interface UsageSummary {
  totalCost?: number;
  totalTokens?: number;
  requestCount?: number;
  successCount?: number;
  successRate?: number | null;
}

export interface UsageQueryResult {
  items: UsageItem[];
  total: number;
  page: number;
  size: number;
  summary: UsageSummary;
}

export interface UsageFilters {
  from?: string;
  to?: string;
  userId?: string;
  modelId?: string;
  accountId?: string;
  protocol?: string;
  reqType?: string;
  status?: string;
  page?: number;
  size?: number;
}

// ===== 账号池 =====

export async function fetchAccounts(): Promise<AdminAccount[]> {
  const response = await authFetch('/api/nova/admin/accounts', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AdminAccount[];
}

export async function createAccount(dto: Record<string, unknown>): Promise<AdminAccount> {
  const response = await authFetch('/api/nova/admin/accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AdminAccount;
}

export async function updateAccount(id: string, dto: Record<string, unknown>): Promise<AdminAccount> {
  const response = await authFetch(`/api/nova/admin/accounts/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AdminAccount;
}

export async function deleteAccount(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/admin/accounts/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
}

export async function accountAction(id: string, action: 'pause' | 'resume' | 'recover'): Promise<AdminAccount> {
  const response = await authFetch(`/api/nova/admin/accounts/${encodeURIComponent(id)}/${action}`, {
    method: 'POST',
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AdminAccount;
}

/** 测试连通（account.test）——成功清失败计数；返回 {ok, message}。 */
export async function testAccount(id: string): Promise<{ ok: boolean; message?: string }> {
  const response = await authFetch(`/api/nova/admin/accounts/${encodeURIComponent(id)}/test`, {
    method: 'POST',
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as { ok: boolean; message?: string };
}

// ===== 模型目录（admin） =====

export async function fetchCatalogAdmin(): Promise<AdminCatalogModel[]> {
  const response = await authFetch('/api/nova/admin/models', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AdminCatalogModel[];
}

export async function createCatalogModel(dto: Record<string, unknown>): Promise<AdminCatalogModel> {
  const response = await authFetch('/api/nova/admin/models', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AdminCatalogModel;
}

export async function updateCatalogModel(id: string, dto: Record<string, unknown>): Promise<AdminCatalogModel> {
  const response = await authFetch(`/api/nova/admin/models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AdminCatalogModel;
}

export async function deleteCatalogModel(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/admin/models/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
}

// ===== 价格配置 =====

export async function fetchPricing(): Promise<PricingRow[]> {
  const response = await authFetch('/api/nova/admin/pricing', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as PricingRow[];
}

export async function upsertPricing(dto: Record<string, unknown>): Promise<PricingRow> {
  const response = await authFetch('/api/nova/admin/pricing', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as PricingRow;
}

export async function deletePricing(modelId: string, currency?: string): Promise<void> {
  const qs = currency ? `?currency=${encodeURIComponent(currency)}` : '';
  const response = await authFetch(`/api/nova/admin/pricing/${encodeURIComponent(modelId)}${qs}`, { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
}

// ===== 审计与费用 =====

export async function queryUsage(filters: UsageFilters = {}): Promise<UsageQueryResult> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  const qs = params.toString();
  const response = await authFetch(`/api/nova/admin/usage${qs ? `?${qs}` : ''}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as UsageQueryResult;
}

/** CSV 导出（当前筛选条件），返回 Blob（UTF-8 BOM，Excel 友好）。 */
export async function exportUsageCsv(filters: UsageFilters = {}): Promise<Blob> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  const qs = params.toString();
  const response = await authFetch(`/api/nova/admin/usage/export${qs ? `?${qs}` : ''}`);
  if (!response.ok) throw await readApiError(response);
  return await response.blob();
}

// ===== 我的用量（T24, A11：仅本人） =====

export interface MyUsageItem {
  id: number;
  modelId?: string | null;
  modelName?: string;
  accountId?: string | null;
  accountName?: string;
  protocol?: string;
  reqType?: string;
  refType?: string;
  status?: string;
  inputTokens?: number;
  outputTokens?: number;
  images?: number;
  cost?: number;
  currency?: string;
  durationMs?: number;
  createdAt?: string;
}

export interface MyUsageDaily {
  aggDate?: string;
  reqType?: string;
  requestCount?: number;
  successCount?: number;
  inputTokens?: number;
  outputTokens?: number;
  cost?: number;
  currency?: string;
}

export interface MyUsageResult {
  items: MyUsageItem[];
  total: number;
  page: number;
  size: number;
  summary: UsageSummary;
  daily: MyUsageDaily[];
}

/**
 * 用户本人用量（A11）：接口无 userId 参数，后端恒绑定当前登录用户——
 * 前端也不得携带任何目标用户标识。
 */
export async function fetchMyUsage(filters: { from?: string; to?: string; page?: number; size?: number } = {}): Promise<MyUsageResult> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  const qs = params.toString();
  const response = await authFetch(`/api/nova/usage/me${qs ? `?${qs}` : ''}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as MyUsageResult;
}

// ===== 角色与权限（T28, rbac.manage） =====

export interface RbacRole {
  id: string;
  code: string;
  name: string;
  builtin: boolean;
}

export interface RbacPermission {
  id: string;
  code: string;
  type: 'menu' | 'button';
  parentCode: string | null;
  label: string;
  apiPath: string | null;
  sortOrder: number;
}

export async function fetchRoles(): Promise<RbacRole[]> {
  const response = await authFetch('/api/nova/admin/roles', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as RbacRole[];
}

export async function fetchPermissions(): Promise<RbacPermission[]> {
  const response = await authFetch('/api/nova/admin/roles/permissions', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as RbacPermission[];
}

export async function fetchRolePermissions(roleId: string): Promise<{ roleId: string; roleCode: string; permissionIds: string[] }> {
  const response = await authFetch(`/api/nova/admin/roles/${encodeURIComponent(roleId)}/permissions`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as { roleId: string; roleCode: string; permissionIds: string[] };
}

/** 保存角色×权限矩阵（后端写 audit_log + 权限缓存失效，A16/A14）。 */
export async function saveRolePermissions(
  roleId: string,
  permissionIds: string[],
): Promise<{ ok: boolean; roleId: string; before: string[]; after: string[] }> {
  const response = await authFetch(`/api/nova/admin/roles/${encodeURIComponent(roleId)}/permissions`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ permissionIds }),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as { ok: boolean; roleId: string; before: string[]; after: string[] };
}
