'use client';

/**
 * WIN-41 (T11, ARCH Part G.3) — Agent 后端化统一事件流客户端（协议无关）。
 *
 * 前端不再构造 4 协议请求体、不再解析各协议 SSE、不再实现重试/超时/错误分类：
 * 统一 {@code POST /api/nova/agent/conversations/{id}/messages}（{@code Accept: text/event-stream}），
 * 由后端 AgentChatService 完成上下文组装（历史/图片目录/指令/工具 schema/压缩摘要）、
 * 账号池转发、统一事件转译（delta/reasoning/proposal/tool/retry/error/done/ping）与重试。
 *
 * 回退开关：{@code raw: true} → {@code X-Agent-Stream: raw} 原始透传（G.3 对拍/应急，
 * 上游 SSE 不经转译逐字节透传，由调用方按旧 sse-stream-parser 语义自行解析）。
 */

import { authFetch, readApiError } from '@/lib/auth';
import type { AgentProposal } from '@/lib/agent-chat-config';

// ===== 统一事件模型（镜像后端 AgentStreamTranslator） =====

export type UnifiedEventType =
  | 'delta'
  | 'reasoning'
  | 'proposal'
  | 'tool'
  | 'retry'
  | 'error'
  | 'done'
  | 'ping';

export interface UnifiedDoneEvent {
  messageId: string;
  taskId: string;
  proposalId: string;
}

export interface StreamBackendInput {
  /** 用户消息文本（后端组装完整上下文，前端不传历史） */
  text?: string;
  /** 已上传图片 assets id（后端从 assets 读取目录与字节） */
  imageAssetIds?: string[];
  webSearch?: boolean;
  /** 目录文本模型 UUID（后端 resolveTextModel 使用） */
  model: string;
  /** 用户消息客户端 id → 服务端落库主键一致（撤回/回滚 id 稳定） */
  clientMessageId?: string;
  /** 重新编辑起点消息 id（可选，暂未启用） */
  reeditFromMessageId?: string;
  /** raw 透传回退开关（X-Agent-Stream: raw） */
  raw?: boolean;
  /** raw 模式下原样透传的上游请求体 */
  rawRequestBody?: unknown;
}

export interface StreamBackendCallbacks {
  onDelta(token: string): void;
  onReasoning(token: string): void;
  onProposal?(proposal: AgentProposal): void;
  onRetry?(attempt: number, maxAttempts: number, reason?: string): void;
  onDone(done: UnifiedDoneEvent): void;
  onError(err: Error): void;
}

export interface StreamBackendHandle {
  abort(): void;
  promise: Promise<void>;
}

const ACCEPT_SSE = 'text/event-stream';

/** 解析 SSE 行缓冲（event:/data:/空行），对每条完成事件回调。 */
function parseSseChunk(
  buffer: string,
  emit: (event: string, dataLines: string[]) => void,
): string {
  let rest = buffer;
  for (;;) {
    const sep = rest.indexOf('\n\n');
    if (sep === -1) break;
    const block = rest.slice(0, sep);
    rest = rest.slice(sep + 2);
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
      // 忽略注释行（: keepalive）与其他字段
    }
    if (dataLines.length > 0) {
      emit(eventName, dataLines);
    }
  }
  return rest;
}

/**
 * 发送消息 → 后端统一事件流。返回 handle（abort + promise）；
 * promise 在流结束（done/error/abort）后 resolve，绝不 reject（错误走 onError）。
 */
export function streamBackendChat(
  conversationId: string,
  input: StreamBackendInput,
  callbacks: StreamBackendCallbacks,
): StreamBackendHandle {
  const controller = new AbortController();
  const raw = input.raw === true;

  const promise = (async () => {
    let response: Response;
    try {
      const headers: Record<string, string> = { Accept: ACCEPT_SSE };
      if (raw) headers['X-Agent-Stream'] = 'raw';
      const body: Record<string, unknown> = raw
        ? { model: input.model, requestBody: input.rawRequestBody ?? {} }
        : {
            text: input.text ?? '',
            ...(input.imageAssetIds && input.imageAssetIds.length > 0
              ? { imageAssetIds: input.imageAssetIds }
              : {}),
            ...(input.webSearch ? { webSearch: true } : {}),
            model: input.model,
            ...(input.clientMessageId ? { clientMessageId: input.clientMessageId } : {}),
            ...(input.reeditFromMessageId ? { reeditFromMessageId: input.reeditFromMessageId } : {}),
          };
      response = await authFetch(
        `/api/nova/agent/conversations/${encodeURIComponent(conversationId)}/messages`,
        {
          method: 'POST',
          headers,
          body: JSON.stringify(body),
          signal: controller.signal,
        },
      );
    } catch (err) {
      if (controller.signal.aborted) return;
      callbacks.onError(err instanceof Error ? err : new Error(String(err)));
      return;
    }

    if (!response.ok || !response.body) {
      try {
        callbacks.onError(await readApiError(response));
      } catch {
        callbacks.onError(new Error(`请求失败（${response.status}）`));
      }
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    // abort 时立即取消 reader，避免 read() 挂起（Promise 就此结束，不抛错）
    const onAbort = () => { try { void reader.cancel(); } catch { /* ignore */ } };
    controller.signal.addEventListener('abort', onAbort);
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        buffer = parseSseChunk(buffer, (eventName, dataLines) => {
          if (controller.signal.aborted) return;
          const data = parseEventData(dataLines);
          handleUnifiedEvent(eventName, data, callbacks);
        });
      }
      buffer = parseSseChunk(buffer, (eventName, dataLines) => {
        if (controller.signal.aborted) return;
        handleUnifiedEvent(eventName, parseEventData(dataLines), callbacks);
      });
    } catch (err) {
      if (controller.signal.aborted) return;
      callbacks.onError(err instanceof Error ? err : new Error(String(err)));
    } finally {
      try { await reader.cancel(); } catch { /* ignore */ }
    }
  })();

  return {
    abort() {
      controller.abort();
    },
    promise,
  };
}

function parseEventData(dataLines: string[]): unknown {
  const text = dataLines.join('\n');
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function handleUnifiedEvent(
  eventName: string,
  data: unknown,
  callbacks: StreamBackendCallbacks,
): void {
  const obj = data && typeof data === 'object' ? (data as Record<string, unknown>) : null;
  switch (eventName) {
    case 'delta': {
      const text = obj?.text;
      if (typeof text === 'string') callbacks.onDelta(text);
      break;
    }
    case 'reasoning': {
      const text = obj?.text;
      if (typeof text === 'string') callbacks.onReasoning(text);
      break;
    }
    case 'proposal': {
      if (obj && callbacks.onProposal) callbacks.onProposal(obj as unknown as AgentProposal);
      break;
    }
    case 'tool': {
      // 工具事件（propose_image_action 已由后端解析为 proposal；此处透传供调试）
      break;
    }
    case 'retry': {
      if (callbacks.onRetry) {
        callbacks.onRetry(
          typeof obj?.attempt === 'number' ? obj.attempt : 1,
          typeof obj?.maxAttempts === 'number' ? obj.maxAttempts : 3,
          typeof obj?.reason === 'string' ? obj.reason : undefined,
        );
      }
      break;
    }
    case 'error': {
      const message = typeof obj?.message === 'string' ? obj.message : '上游处理失败';
      callbacks.onError(new Error(message));
      break;
    }
    case 'done': {
      callbacks.onDone({
        messageId: typeof obj?.messageId === 'string' ? obj.messageId : '',
        taskId: typeof obj?.taskId === 'string' ? obj.taskId : '',
        proposalId: typeof obj?.proposalId === 'string' ? obj.proposalId : '',
      });
      break;
    }
    case 'ping':
    default:
      break;
  }
}

/**
 * 图片描述（后端托管，替代前端 vision 直连）：POST /api/nova/agent/describe。
 *
 * @param assetId 已上传 assets id
 * @param model   目录文本模型 UUID（可选，缺省由后端取默认）
 */
export async function describeAsset(assetId: string, model?: string): Promise<string> {
  const response = await authFetch('/api/nova/agent/describe', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(model ? { assetId, model } : { assetId }),
  });
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { description?: string };
  return data.description ?? '';
}
