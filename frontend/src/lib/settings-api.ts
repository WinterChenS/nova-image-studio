'use client';

/**
 * 设置/模型 API 数据层（M2 T2.1/T2.3）——对接：
 *   GET/POST/PUT/DELETE /api/nova/models
 *   GET/PUT /api/nova/settings, POST /api/nova/settings/import
 *
 * 模型 ID 由服务端分配（UUID）；写路径可传掩码 Key（sk-***last4）表示“沿用
 * 已存密钥”。`persistRegistryToApi` 通过 diff 当前/先前注册表生成增删改。
 */

import { authFetch, readApiError } from '@/lib/auth';
import {
  ensureDefaults,
  type DefaultModels,
  type ImageModelConfig,
  type NovaModelRegistry,
  type TextModelConfig,
} from '@/lib/nova-models';
import type { ProviderProtocol } from '@/lib/nova-models';
import type { TextProviderProtocol } from '@/lib/nova-text-protocol';

export interface ServerModel {
  id: string;
  type: 'image' | 'text';
  protocol: string;
  name: string;
  modelId: string;
  apiKey: string; // 掩码或空
  baseUrl: string;
  builtinPreset?: string;
  maxRefImages?: number;
  maxOutputSize?: string;
  supportsAdvancedParams?: boolean;
  note?: string;
}

// ===== models =====

export async function fetchModels(): Promise<ServerModel[]> {
  const response = await authFetch('/api/nova/models', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerModel[];
}

export async function createModel(dto: Record<string, unknown>): Promise<ServerModel> {
  const response = await authFetch('/api/nova/models', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerModel;
}

export async function updateModel(id: string, dto: Record<string, unknown>): Promise<ServerModel> {
  const response = await authFetch(`/api/nova/models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerModel;
}

export async function deleteModel(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/models/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
}

// ===== settings =====

export async function fetchSettings(): Promise<Record<string, unknown>> {
  const response = await authFetch('/api/nova/settings', { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as Record<string, unknown>;
}

export async function saveSettings(patch: Record<string, unknown>): Promise<void> {
  const response = await authFetch('/api/nova/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!response.ok) throw await readApiError(response);
}

/** 旧 localStorage 导出 JSON 一键导入（T2.6）。 */
export async function importSettings(legacyExport: Record<string, unknown>): Promise<{ modelsCreated: number; settingsWritten: number }> {
  const response = await authFetch('/api/nova/settings/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(legacyExport),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as { modelsCreated: number; settingsWritten: number };
}

/**
 * 备份恢复（T2.7）：把 serverSettings.json 里的模型/设置包恢复到当前用户。
 * 模型重建（新 UUID），设置整包 PUT。
 */
export async function restoreServerSettings(models: ServerModel[], settings: Record<string, unknown>): Promise<void> {
  for (const m of models) {
    const dto: Record<string, unknown> = {
      type: m.type,
      protocol: m.protocol,
      name: m.name,
      modelId: m.modelId,
      apiKey: m.apiKey,
      baseUrl: m.baseUrl,
    };
    if (m.type === 'image') {
      dto.builtinPreset = m.builtinPreset;
      dto.maxRefImages = m.maxRefImages;
      dto.maxOutputSize = m.maxOutputSize;
      dto.supportsAdvancedParams = m.supportsAdvancedParams;
    } else {
      dto.note = m.note;
    }
    await createModel(dto);
  }
  const allowed = Object.fromEntries(
    Object.entries(settings).filter(([key]) => /^(registry|workbench|limit|gallery|agent)\./.test(key)),
  );
  if (Object.keys(allowed).length > 0) {
    await saveSettings(allowed);
  }
}

// ===== registry 映射 =====

export function isServerUuid(id: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id);
}

function toImageConfig(m: ServerModel): ImageModelConfig {
  return {
    id: m.id,
    protocol: m.protocol as ProviderProtocol,
    name: m.name,
    modelId: m.modelId,
    apiKey: m.apiKey,
    baseUrl: m.baseUrl,
    builtinPreset: (m.builtinPreset || 'gpt-image-2') as ImageModelConfig['builtinPreset'],
    maxRefImages: m.maxRefImages ?? 0,
    maxOutputSize: (m.maxOutputSize || '1K') as ImageModelConfig['maxOutputSize'],
    supportsAdvancedParams: Boolean(m.supportsAdvancedParams),
  };
}

function toTextConfig(m: ServerModel): TextModelConfig {
  return {
    id: m.id,
    protocol: m.protocol as TextProviderProtocol,
    name: m.name,
    modelId: m.modelId,
    apiKey: m.apiKey,
    baseUrl: m.baseUrl,
    note: m.note || '',
  };
}

/** 服务端模型列表 + registry.defaults → 前端注册表。 */
export function modelsToRegistry(models: ServerModel[], defaults?: Partial<DefaultModels>): NovaModelRegistry {
  const imageModels = models.filter((m) => m.type === 'image').map(toImageConfig);
  const textModels = models.filter((m) => m.type === 'text').map(toTextConfig);
  return {
    imageModels,
    textModels,
    defaults: ensureDefaults(defaults || {}, imageModels, textModels),
  };
}

/** 从 API 拉取并组装完整注册表（T2.3 数据层主入口）。 */
export async function loadRegistryFromApi(): Promise<NovaModelRegistry> {
  const [models, settings] = await Promise.all([fetchModels(), fetchSettings()]);
  const defaults = settings['registry.defaults'] as Partial<DefaultModels> | undefined;
  return modelsToRegistry(models, defaults);
}

function imageToDto(m: ImageModelConfig): Record<string, unknown> {
  return {
    type: 'image',
    protocol: m.protocol,
    name: m.name,
    modelId: m.modelId,
    apiKey: m.apiKey,
    baseUrl: m.baseUrl,
    builtinPreset: m.builtinPreset,
    maxRefImages: m.maxRefImages,
    maxOutputSize: m.maxOutputSize,
    supportsAdvancedParams: m.supportsAdvancedParams,
  };
}

function textToDto(m: TextModelConfig): Record<string, unknown> {
  return {
    type: 'text',
    protocol: m.protocol,
    name: m.name,
    modelId: m.modelId,
    apiKey: m.apiKey,
    baseUrl: m.baseUrl,
    note: m.note || '',
  };
}

/**
 * 保存注册表到 API（T2.3）——diff 先前/当前：
 * 新增（非 UUID id）→ create；UUID id → update；先前存在但当前缺失 → delete。
 * defaults 作为 registry.defaults 写回设置包。
 */
export async function persistRegistryToApi(registry: NovaModelRegistry, previous?: NovaModelRegistry): Promise<void> {
  const prev = previous || { imageModels: [], textModels: [], defaults: ensureDefaults({}, registry.imageModels, registry.textModels) };
  const prevIds = new Set([...prev.imageModels, ...prev.textModels].map((m) => m.id));
  const nextIds = new Set([...registry.imageModels, ...registry.textModels].map((m) => m.id));

  for (const model of registry.imageModels) {
    if (isServerUuid(model.id)) {
      await updateModel(model.id, imageToDto(model));
    } else {
      await createModel(imageToDto(model));
    }
  }
  for (const model of registry.textModels) {
    if (isServerUuid(model.id)) {
      await updateModel(model.id, textToDto(model));
    } else {
      await createModel(textToDto(model));
    }
  }
  for (const id of prevIds) {
    if (!nextIds.has(id)) {
      await deleteModel(id);
    }
  }
  await saveSettings({ 'registry.defaults': registry.defaults });
}

// ===== 工作台默认值（workbench.*）读写 =====

export const WORKBENCH_SETTING_KEYS = {
  t2i: 'workbench.t2i',
  i2i: 'workbench.i2i',
  reverse: 'workbench.reverse',
  agent: 'workbench.agent',
  gif: 'workbench.gif',
} as const;

export async function fetchSetting<T>(key: string, fallback: T): Promise<T> {
  try {
    const settings = await fetchSettings();
    const value = settings[key];
    return value === undefined ? fallback : (value as T);
  } catch {
    return fallback;
  }
}

export async function saveSetting(key: string, value: unknown): Promise<void> {
  await saveSettings({ [key]: value });
}
