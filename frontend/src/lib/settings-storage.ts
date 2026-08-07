'use client';

import { getCompleteImageModels, getCompleteTextModels, loadRegistry } from '@/lib/nova-models';
import { fetchSetting, saveSetting, WORKBENCH_SETTING_KEYS } from '@/lib/settings-api';

export function getStoredApiKey(): string {
  const registry = loadRegistry();
  const imageModel = getCompleteImageModels(registry)[0];
  const textModel = getCompleteTextModels(registry)[0];
  return imageModel?.apiKey || textModel?.apiKey || '';
}

export function setStoredApiKey(): boolean {
  return true;
}

export function removeStoredApiKey(): void {
  // 开源版改为模型级别独立存储，不再提供全局 key 写入口。
}

export const getStoredCcodeKey = getStoredApiKey;
export const setStoredCcodeKey = setStoredApiKey;
export const removeStoredCcodeKey = removeStoredApiKey;

export function getApiKeyFromStorage(): string {
  return getStoredApiKey();
}

export function hasAnyApiKey(): boolean {
  const registry = loadRegistry();
  // WIN-25 (T18): 目录模型 apiKey 为 'catalog' 标记 —— 只要存在任一可用模型即解锁
  const models = [...getCompleteImageModels(registry), ...getCompleteTextModels(registry)];
  return models.some((m) => m.available !== false);
}

export function loadJsonFromStorage<T>(key: string): Partial<T> {
  if (typeof window === 'undefined') return {};
  const raw = localStorage.getItem(key);
  return raw ? JSON.parse(raw) : {};
}

export function saveJsonToStorage<T>(key: string, value: T): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(key, JSON.stringify(value));
}

/**
 * M2 (T2.3)：表单默认值改走设置 API（workbench.*），不再读写 localStorage。
 * 这两个函数是旧 localStorage 路径（仅迁移向导/UI 偏好使用）。
 */
export async function loadApiJsonSetting<T>(key: string, fallback: T): Promise<T> {
  return fetchSetting<T>(key, fallback);
}

/** 异步写回设置 API（fire-and-forget，失败静默——下次保存会重试）。 */
export function saveApiJsonSetting<T>(key: string, value: T): void {
  void saveSetting(key, value).catch(() => {
    // 忽略（网络/未登录），表单仍可继续工作
  });
}

/** 工作台表单默认值 → 设置 API key 映射（ARCH C.4 workbench.* 命名空间）。 */
export function workbenchSettingKey(legacyKey: string): string {
  const mapping: Record<string, string> = {
    'nova-t2i-settings': WORKBENCH_SETTING_KEYS.t2i,
    'nova-i2i-settings': WORKBENCH_SETTING_KEYS.i2i,
    'nova-reverse-prompt-settings': WORKBENCH_SETTING_KEYS.reverse,
    'nova-gif-settings': WORKBENCH_SETTING_KEYS.gif,
    'nova-agent-params': WORKBENCH_SETTING_KEYS.agent,
  };
  return mapping[legacyKey] || legacyKey;
}
