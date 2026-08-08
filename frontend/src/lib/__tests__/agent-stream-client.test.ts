import { beforeEach, describe, expect, it, vi } from 'vitest';
import { describeAsset, streamBackendChat, type StreamBackendCallbacks } from '@/lib/agent-stream-client';
import { authFetch } from '@/lib/auth';

vi.mock('@/lib/auth', async importOriginal => {
  const actual = await importOriginal<typeof import('@/lib/auth')>();
  return { ...actual, authFetch: vi.fn(), getAuthHeaders: vi.fn(() => ({})) };
});

const mockedAuthFetch = vi.mocked(authFetch);

/** 构造一个 SSE Response（body 为 ReadableStream，逐块推送后关闭）。 */
function sseResponse(events: { event: string; data: unknown }[]): Response {
  const encoder = new TextEncoder();
  const chunks: Uint8Array[] = [];
  for (const ev of events) {
    chunks.push(encoder.encode(`event: ${ev.event}\n`));
    chunks.push(encoder.encode(`data: ${JSON.stringify(ev.data)}\n\n`));
  }
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk);
      controller.close();
    },
  });
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function collectCallbacks(): {
  callbacks: StreamBackendCallbacks;
  events: Record<string, unknown[]>;
} {
  const events: Record<string, unknown[]> = { delta: [], reasoning: [], proposal: [], retry: [], done: [], error: [] };
  const callbacks: StreamBackendCallbacks = {
    onDelta: token => events.delta.push(token),
    onReasoning: token => events.reasoning.push(token),
    onProposal: proposal => events.proposal.push(proposal),
    onRetry: (attempt, maxAttempts) => events.retry.push({ attempt, maxAttempts }),
    onDone: done => events.done.push(done),
    onError: err => events.error.push(err.message),
  };
  return { callbacks, events };
}

describe('streamBackendChat（WIN-41 T11 统一事件流客户端）', () => {
  beforeEach(() => {
    mockedAuthFetch.mockReset();
  });

  it('POST /messages 携带 Accept: text/event-stream 与最小请求体（不传历史/协议）', async () => {
    mockedAuthFetch.mockResolvedValue(sseResponse([
      { event: 'delta', data: { text: '你好' } },
      { event: 'done', data: { messageId: 'm-1', taskId: '', proposalId: '' } },
    ]));
    const { callbacks } = collectCallbacks();

    const handle = streamBackendChat('conv-1', {
      text: '画一只猫',
      imageAssetIds: ['a1'],
      webSearch: true,
      model: 'catalog-uuid-1',
      clientMessageId: 'user-1',
    }, callbacks);
    await handle.promise;

    expect(mockedAuthFetch).toHaveBeenCalledTimes(1);
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/agent/conversations/conv-1/messages');
    const headers = new Headers(init.headers);
    expect(String(headers.get('Accept') ?? '')).toContain('text/event-stream');
    expect(headers.get('X-Agent-Stream')).toBeNull();
    const body = JSON.parse(String(init.body));
    expect(body).toEqual({
      text: '画一只猫',
      imageAssetIds: ['a1'],
      webSearch: true,
      model: 'catalog-uuid-1',
      clientMessageId: 'user-1',
    });
    expect(body.history).toBeUndefined();
    expect(body.protocol).toBeUndefined();
  });

  it('按序派发 delta/reasoning/proposal/retry/done 统一事件', async () => {
    mockedAuthFetch.mockResolvedValue(sseResponse([
      { event: 'reasoning', data: { text: '思考中' } },
      { event: 'delta', data: { text: '正在' } },
      { event: 'delta', data: { text: '生成' } },
      { event: 'proposal', data: { action: 'generate', prompt: '一只猫', reason: '想生成', referencedImageIds: [], requestedModelId: 'm9' } },
      { event: 'done', data: { messageId: '', taskId: '', proposalId: '' } },
    ]));
    const { callbacks, events } = collectCallbacks();

    await streamBackendChat('conv-1', { text: 'x', model: 'catalog-uuid-1' }, callbacks).promise;

    expect(events.reasoning).toEqual(['思考中']);
    expect(events.delta).toEqual(['正在', '生成']);
    expect(events.proposal).toHaveLength(1);
    expect(events.done).toEqual([{ messageId: '', taskId: '', proposalId: '' }]);
    expect(events.error).toEqual([]);
  });

  it('retry 事件透传 attempt/maxAttempts', async () => {
    mockedAuthFetch.mockResolvedValue(sseResponse([
      { event: 'retry', data: { attempt: 2, maxAttempts: 3, reason: '上游 429' } },
      { event: 'delta', data: { text: 'ok' } },
      { event: 'done', data: { messageId: 'm-9', taskId: '', proposalId: '' } },
    ]));
    const { callbacks, events } = collectCallbacks();

    await streamBackendChat('conv-1', { text: 'x', model: 'catalog-uuid-1' }, callbacks).promise;

    expect(events.retry).toEqual([{ attempt: 2, maxAttempts: 3 }]);
    expect(events.done).toEqual([{ messageId: 'm-9', taskId: '', proposalId: '' }]);
  });

  it('error 事件 → onError 携带后端消息', async () => {
    mockedAuthFetch.mockResolvedValue(sseResponse([
      { event: 'error', data: { code: 'UPSTREAM_ERROR', message: '上游不可用', retryable: false } },
    ]));
    const { callbacks, events } = collectCallbacks();

    await streamBackendChat('conv-1', { text: 'x', model: 'catalog-uuid-1' }, callbacks).promise;

    expect(events.error).toEqual(['上游不可用']);
  });

  it('HTTP 非 2xx → onError 使用 readApiError 语义', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(409, { error: '该会话正在生成中，请稍后再试' }));
    const { callbacks, events } = collectCallbacks();

    await streamBackendChat('conv-1', { text: 'x', model: 'catalog-uuid-1' }, callbacks).promise;

    expect(events.error).toEqual(['该会话正在生成中，请稍后再试']);
  });

  it('raw 回退模式携带 X-Agent-Stream: raw 并原样透传 requestBody', async () => {
    mockedAuthFetch.mockResolvedValue(sseResponse([
      { event: 'delta', data: { text: 'raw' } },
      { event: 'done', data: { messageId: '', taskId: '', proposalId: '' } },
    ]));
    const { callbacks } = collectCallbacks();
    const rawBody = { model: 'gpt-x', messages: [{ role: 'user', content: 'hi' }] };

    await streamBackendChat('conv-1', {
      model: 'catalog-uuid-1',
      raw: true,
      rawRequestBody: rawBody,
      text: 'ignored-in-raw',
    }, callbacks).promise;

    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/agent/conversations/conv-1/messages');
    expect(new Headers(init.headers).get('X-Agent-Stream')).toBe('raw');
    const body = JSON.parse(String(init.body));
    expect(body.requestBody).toEqual(rawBody);
    expect(body.model).toBe('catalog-uuid-1');
  });

  it('abort() 中断请求并结束 promise（不抛错）', async () => {
    let aborted = false;
    mockedAuthFetch.mockImplementation((_url: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal;
      // 模拟浏览器：fetch signal abort → 响应流关闭
      const stream = new ReadableStream<Uint8Array>({
        start(c) {
          signal?.addEventListener('abort', () => {
            aborted = true;
            try { void c.close(); } catch { /* ignore */ }
          });
        },
      });
      return Promise.resolve(new Response(stream, { status: 200 }));
    });
    const { callbacks } = collectCallbacks();

    const handle = streamBackendChat('conv-1', { text: 'x', model: 'catalog-uuid-1' }, callbacks);
    handle.abort();
    await handle.promise;

    expect(aborted).toBe(true);
  });

  it('describeAsset POST /describe 并返回 description', async () => {
    mockedAuthFetch.mockResolvedValue(jsonResponse(200, { description: '一只橘猫' }));

    const description = await describeAsset('asset-9', 'catalog-uuid-1');

    expect(mockedAuthFetch).toHaveBeenCalledTimes(1);
    const [url, init] = mockedAuthFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/nova/agent/describe');
    expect(JSON.parse(String(init.body))).toEqual({ assetId: 'asset-9', model: 'catalog-uuid-1' });
    expect(description).toBe('一只橘猫');
  });
});
