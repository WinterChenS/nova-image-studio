import type { AspectRatio, OutputSize } from '@/lib/gemini-config';
import type { GptImageBackground, GptImageQuality, GptImageStyle } from '@/lib/model-capabilities';
import {
  getCompleteImageModels,
  getCompleteTextModels,
  getImageModelById,
  getTextModelById,
  loadRegistry,
  type ProviderProtocol,
} from '@/lib/nova-models';
import type { TextProviderProtocol } from '@/lib/nova-text-protocol';
import { getAuthHeaders } from '@/lib/auth';

export interface ImageReference {
  data: string;
  mimeType: string;
}

export interface ModelStatus {
  modelId: string;
  available: boolean;
  actualName?: string;
  message?: string;
}

const MODEL_CHECK_TIMEOUT = 30000;
const TASK_REQUEST_TIMEOUT = 30000;
const CREATE_TASK_TIMEOUT = 60000;

export type NovaTaskMode = 'text-to-image' | 'image-to-image';
export type NovaTaskStatus = 'queued' | '排队中' | 'processing' | 'completed' | 'failed' | 'expired';

export interface CreateNovaTaskInput {
  // M2 (T2.4): apiKey/baseUrl/protocol 不再由前端传递 —— 服务端按 modelId
  // （注册表 UUID）从 DB 解析协议/Key。
  mode: NovaTaskMode;
  prompt: string;
  outputSize: OutputSize;
  customSize?: string;
  aspectRatio: AspectRatio;
  temperature: number;
  model: string; // 注册表模型 UUID
  gptImageQuality?: GptImageQuality;
  gptImageStyle?: GptImageStyle;
  gptImageBackground?: GptImageBackground;
  parallelCount: number;
  images: ImageReference[];
  projectId?: string; // WIN-22 (F-4): 当前项目上下文（生成任务归属）
}

export interface NovaTaskResponse {
  id: string;
  status: NovaTaskStatus;
  mode?: NovaTaskMode;
  result?: { images?: string[] };
  error?: string;
  warning?: string;
  createdAt?: string;
  completedAt?: string;
  expiresAt?: string;
  projectId?: string; // WIN-22 (F-4)
}

export interface NovaQueueStatus {
  concurrencyLimit: number;
  configuredConcurrency: number;
  processingCount: number;
  queuedCount: number;
  pendingCount?: number;
  maxQueueSize?: number;
  remainingQueueSlots?: number;
  displayConcurrency: number;
  displayQueued: number;
  acceptingNewTasks: boolean;
  rateLimitWindowMs?: number;
  rateLimitMaxRequestsPerIp?: number;
  rateLimitMaxRequestsPerApiKey?: number;
  retryAfterSeconds?: number;
  serverMessage?: string;
}

export class NovaTaskError extends Error {
  statusCode: number;
  code?: string;
  retryAfter?: number;

  constructor(message: string, statusCode: number, code?: string, retryAfter?: number) {
    super(message);
    this.name = 'NovaTaskError';
    this.statusCode = statusCode;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

interface CreateTaskResponse {
  taskId?: string;
}

function getObjectProperty(data: unknown, key: string): unknown {
  return typeof data === 'object' && data !== null && key in data
    ? (data as Record<string, unknown>)[key]
    : undefined;
}

async function parseTaskResponse<T>(response: Response): Promise<T> {
  const data: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const error = getObjectProperty(data, 'error');
    const code = getObjectProperty(data, 'code');
    const retryAfter = getObjectProperty(data, 'retryAfter');
    throw new NovaTaskError(
      typeof error === 'string' ? error : `任务请求失败: ${response.status}`,
      response.status,
      typeof code === 'string' ? code : undefined,
      typeof retryAfter === 'number' ? retryAfter : undefined,
    );
  }
  return data as T;
}

function getErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function isAbortError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'name' in error &&
    (error as { name?: unknown }).name === 'AbortError'
  );
}

function normalizeModelCheckError(error: unknown): Error {
  const errorMessage = getErrorMessage(error);
  const lowerMessage = errorMessage.toLowerCase();

  if (
    lowerMessage.includes('timeout') ||
    lowerMessage.includes('timed out') ||
    lowerMessage.includes('abort') ||
    lowerMessage.includes('请求超时')
  ) {
    return new Error('模型检查超时，请稍后重试。');
  }

  if (
    lowerMessage.includes('failed to fetch') ||
    lowerMessage.includes('fetch failed') ||
    lowerMessage.includes('networkerror') ||
    lowerMessage.includes('network request failed') ||
    lowerMessage.includes('load failed') ||
    lowerMessage.includes('network connection was lost') ||
    lowerMessage.includes('econnreset') ||
    lowerMessage.includes('socket hang up') ||
    lowerMessage.includes('terminated')
  ) {
    return new Error('网络连接失败。请检查网络连接或稍后重试。');
  }

  return error instanceof Error ? error : new Error(errorMessage);
}

async function fetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs: number = MODEL_CHECK_TIMEOUT,
): Promise<Response> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

  try {
    // M2 (T2.5): 核心任务路径统一注入 JWT（任务创建/读取/ack 均需登录）
    const headers = new Headers(init.headers || {});
    for (const [k, v] of Object.entries(getAuthHeaders())) {
      headers.set(k, v);
    }
    return await fetch(input, {
      ...init,
      headers,
      signal: controller.signal,
    });
  } catch (error) {
    if (isAbortError(error)) {
      throw new Error('请求超时');
    }

    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

export async function createNovaTask(input: CreateNovaTaskInput): Promise<string> {
  const response = await fetchWithTimeout('/api/nova/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  }, CREATE_TASK_TIMEOUT);
  const data = await parseTaskResponse<CreateTaskResponse>(response);
  if (!data?.taskId) throw new Error('创建任务失败：后端未返回任务 ID');
  return data.taskId;
}

export async function checkModelsAvailability(
  targetModelIds?: string[],
): Promise<ModelStatus[]> {
  try {
    const registry = loadRegistry();
    const completeImageModels = getCompleteImageModels(registry);
    const completeTextModels = getCompleteTextModels(registry);
    const configuredModels = [
      ...completeImageModels.map((model) => ({ id: model.id, name: model.name, protocol: model.protocol, modelId: model.modelId })),
      ...completeTextModels.map((model) => ({ id: model.id, name: model.name, protocol: model.protocol, modelId: model.modelId })),
    ];

    const filteredModels = targetModelIds && targetModelIds.length > 0
      ? configuredModels.filter((model) => targetModelIds.includes(model.id))
      : configuredModels;

    if (filteredModels.length === 0) {
      return [];
    }

    // M2 (T2.4): 服务端按 modelId 解析配置与 Key（/api/nova/proxy/models?modelId=）。
    return Promise.all(filteredModels.map(async (model) => {
      try {
        const response = await fetch(`/api/nova/proxy/models?modelId=${encodeURIComponent(model.id)}`, {
          method: 'GET',
          cache: 'no-store',
          headers: authHeaders(),
        });
        if (!response.ok) {
          const detail = await response.text().catch(() => '');
          return {
            modelId: model.id,
            actualName: model.name,
            available: false,
            message: `${response.status}${detail ? ` ${detail.slice(0, 120)}` : ''}`,
          };
        }
        const data = await response.json().catch(() => ({})) as {
          data?: Array<{ id?: string; model?: string }>;
          models?: Array<{ name?: string }>;
        };
        const exists = (
          (Array.isArray(data.data) && data.data.some(
            (item) => String(item?.id || item?.model || '') === model.modelId,
          ))
          || (Array.isArray(data.models) && data.models.some(
            (item) => String(item?.name || '').replace(/^models\//, '') === model.modelId,
          ))
        );
        return {
          modelId: model.id,
          actualName: model.name,
          available: exists,
          message: exists ? model.modelId : `未在 /models 中找到 ${model.modelId}`,
        };
      } catch (error) {
        return {
          modelId: model.id,
          actualName: model.name,
          available: false,
          message: getErrorMessage(error),
        };
      }
    }));
  } catch (error) {
    throw normalizeModelCheckError(error);
  }
}

function authHeaders(): Record<string, string> {
  return getAuthHeaders();
}

export function resolveImageTaskProvider(modelId: string): { protocol: ProviderProtocol; modelId: string } {
  // M2 (T2.4): 仅用于检查模型是否可解析（key 由服务端解析），不再返回密钥。
  const registry = loadRegistry();
  const model = getImageModelById(registry, modelId);
  if (!model) throw new Error(`未找到图片模型配置: ${modelId}`);
  return { protocol: model.protocol, modelId: model.modelId };
}

/**
 * 已配置图片模型；未配置/不可用返回 null。WIN-25 (T18)：目录模型以
 * {@code available} 为判定（apiKey 由服务端账号池持有，不再前端判定）。
 */
export function getConfiguredImageModel(modelId: string): import('@/lib/nova-models').ImageModelConfig | null {
  const registry = loadRegistry();
  const model = getImageModelById(registry, modelId);
  if (!model) return null;
  if (model.available === false) return null;   // A5: 无可用账号/enabled=false 不可选
  return model.apiKey ? model : null;
}

export function resolveTextTaskProvider(modelId: string): { protocol: TextProviderProtocol } {
  const registry = loadRegistry();
  const model = getTextModelById(registry, modelId);
  if (!model) throw new Error(`未找到文本模型配置: ${modelId}`);
  return { protocol: model.protocol };
}

export async function getNovaTask(taskId: string): Promise<NovaTaskResponse> {
  const response = await fetchWithTimeout(`/api/nova/tasks/${encodeURIComponent(taskId)}`, {
    method: 'GET',
    cache: 'no-store',
  }, TASK_REQUEST_TIMEOUT);
  return parseTaskResponse(response);
}

export async function getNovaQueueStatus(): Promise<NovaQueueStatus> {
  const response = await fetchWithTimeout('/api/nova/queue-status', {
    method: 'GET',
    cache: 'no-store',
  }, TASK_REQUEST_TIMEOUT);
  return parseTaskResponse(response);
}

export async function ackNovaTask(taskId: string): Promise<void> {
  await fetch(`/api/nova/tasks/${encodeURIComponent(taskId)}/ack`, {
    method: 'POST',
    headers: getAuthHeaders(),
  }).catch(() => undefined);
}

// ===== 向后兼容别名 =====
/** @deprecated Use NovaTaskMode */
export type CcodeTaskMode = NovaTaskMode;
/** @deprecated Use NovaTaskStatus */
export type CcodeTaskStatus = NovaTaskStatus;
/** @deprecated Use CreateNovaTaskInput */
export type CreateCcodeTaskInput = CreateNovaTaskInput;
/** @deprecated Use NovaTaskResponse */
export type CcodeTaskResponse = NovaTaskResponse;
/** @deprecated Use NovaQueueStatus */
export type CcodeQueueStatus = NovaQueueStatus;
/** @deprecated Use NovaTaskError */
export const CcodeTaskError = NovaTaskError;
/** @deprecated Use createNovaTask */
export const createCcodeTask = createNovaTask;
/** @deprecated Use checkModelsAvailability */
export const checkCcodeModelsAvailability = checkModelsAvailability;
/** @deprecated Use getNovaTask */
export const getCcodeTask = getNovaTask;
/** @deprecated Use getNovaQueueStatus */
export const getCcodeQueueStatus = getNovaQueueStatus;
/** @deprecated Use ackNovaTask */
export const ackCcodeTask = ackNovaTask;
