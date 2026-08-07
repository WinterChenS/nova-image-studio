'use client';

/**
 * WIN-22 (T10) — 项目管理 API 数据层（对接 /api/nova/projects）。
 * 默认项目懒创建在服务端（首次 GET 即返回「默认项目」，R-1）。
 */

import { authFetch, readApiError } from '@/lib/auth';

export interface ProjectDto {
  id: string;
  name: string;
  description?: string | null;
  archived: boolean;
  sortOrder: number;
  autoSave: boolean;
  createdAt: string;
  updatedAt: string;
  taskCount: number;
  assetCount: number;
  lastActivityAt?: string | null;
}

export async function fetchProjects(includeArchived = false): Promise<ProjectDto[]> {
  const query = includeArchived ? '?includeArchived=true' : '';
  const response = await authFetch(`/api/nova/projects${query}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ProjectDto[];
}

export interface CreateProjectInput {
  name: string;
  description?: string;
  sortOrder?: number;
}

export async function createProject(input: CreateProjectInput): Promise<ProjectDto> {
  const response = await authFetch('/api/nova/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ProjectDto;
}

export interface UpdateProjectInput {
  name?: string;
  description?: string;
  archived?: boolean;
  sortOrder?: number;
  autoSave?: boolean;
}

export async function updateProject(id: string, input: UpdateProjectInput): Promise<ProjectDto> {
  const response = await authFetch(`/api/nova/projects/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ProjectDto;
}

/** 删除项目；force=false 时项目内有素材返回 409（提示先迁移）。 */
export async function deleteProject(id: string, force = false): Promise<void> {
  const response = await authFetch(`/api/nova/projects/${encodeURIComponent(id)}?force=${force}`, {
    method: 'DELETE',
  });
  if (!response.ok) throw await readApiError(response);
}

export async function setDefaultProject(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/projects/${encodeURIComponent(id)}/default`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  });
  if (!response.ok) throw await readApiError(response);
}
