'use client';

/**
 * WIN-39 (WIN-40 T4) — Agent 会话云端持久化 API 客户端（对接 /api/nova/agent/*）。
 * 取代 IndexedDB（nova-agent-db）作为会话/消息/pending/图片目录的主存储（服务端唯一数据源）。
 * 消息 imageIds 引用 assets.id；会话图片目录 = assets WHERE source_kind='conversation' AND source_ref=<conversationId>。
 */

import { authFetch, readApiError } from '@/lib/auth';
import type { AgentImageRecord, AgentMessage, AgentProposalData } from '@/lib/agent-chat-config';

// ===== 类型 =====

export type ConversationStatus = 'active' | 'archived' | 'deleted';

export interface ServerConversation {
  id: string;
  title: string;
  status: ConversationStatus;
  imageModel: string | null;
  webSearch: boolean;
  pending: ServerPending | null;
  contextSummary: ServerContextSummary | null;
  deletedAt: string | null;
  createdAt: string;
  updatedAt: string;
  lastMessageAt: string | null;
  images?: ServerConversationImage[];
}

/** 服务端 pending 恢复态（FR-1.2）：{kind:'proposal'|'generation', ...前端数据} */
export type ServerPending = { kind: 'proposal' | 'generation'; [key: string]: unknown };

/** 自动上下文压缩摘要（ADR-44）：{text,model,foldedBeforeMessageId,foldedCount,foldedAt} */
export interface ServerContextSummary {
  text?: string;
  model?: string;
  foldedBeforeMessageId?: string;
  foldedCount?: number;
  foldedAt?: string;
}

export interface ServerConversationImage {
  assetId: string;
  source: 'uploaded' | 'asset' | 'generated';
  description: string | null;
  mimeType: string | null;
  width: number | null;
  height: number | null;
}

export interface ServerMessage {
  id: string;
  conversationId: string;
  role: 'user' | 'assistant' | 'system-note' | 'context-divider';
  text: string;
  reasoning?: string | null;
  imageIds: string[];
  taskId?: string | null;
  proposalData?: AgentProposalData | null;
  webSearchUsed?: boolean | null;
  withdrawable: boolean;
  createdAt: string;
}

export interface AgentConversationListResult {
  items: ServerConversation[];
  nextBefore: string | null;
}

export interface AgentMessageListResult {
  items: ServerMessage[];
  nextBefore: string | null;
}

export interface ServerAssetLike {
  id: string;
  assetId?: string;
  name?: string | null;
  mimeType?: string | null;
  width?: number | null;
  height?: number | null;
  sourceKind: string;
  sourceRef?: string | null;
  extra?: Record<string, unknown>;
}

// ===== conversations =====

export async function listConversations(params: {
  status?: string;
  before?: string;
  limit?: number;
} = {}): Promise<AgentConversationListResult> {
  const search = new URLSearchParams();
  if (params.status) search.set('status', params.status);
  if (params.before) search.set('before', params.before);
  if (params.limit) search.set('limit', String(params.limit));
  const query = search.toString();
  const response = await authFetch(`/api/nova/agent/conversations${query ? `?${query}` : ''}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AgentConversationListResult;
}

export async function createConversation(title?: string): Promise<ServerConversation> {
  const response = await authFetch('/api/nova/agent/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(title ? { title } : {}),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerConversation;
}

export async function getConversation(id: string): Promise<ServerConversation> {
  const response = await authFetch(`/api/nova/agent/conversations/${encodeURIComponent(id)}`, { cache: 'no-store' });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerConversation;
}

export async function patchConversation(
  id: string,
  patch: {
    title?: string;
    status?: ConversationStatus;
    imageModel?: string | null;
    webSearch?: boolean;
    pending?: ServerPending | null;
    contextSummary?: ServerContextSummary | null;
  },
): Promise<ServerConversation> {
  const response = await authFetch(`/api/nova/agent/conversations/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerConversation;
}

export async function softDeleteConversation(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/agent/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!response.ok) throw await readApiError(response);
}

export async function restoreConversation(id: string): Promise<void> {
  const response = await authFetch(`/api/nova/agent/conversations/${encodeURIComponent(id)}/restore`, { method: 'POST' });
  if (!response.ok) throw await readApiError(response);
}

// ===== messages =====

/** 消息分页（默认最近 50 条；before 游标加载更早，FR-1.3 懒加载）。 */
export async function listMessages(conversationId: string, params: { before?: string; limit?: number } = {}): Promise<AgentMessageListResult> {
  const search = new URLSearchParams();
  if (params.before) search.set('before', params.before);
  if (params.limit) search.set('limit', String(params.limit));
  const query = search.toString();
  const response = await authFetch(
    `/api/nova/agent/conversations/${encodeURIComponent(conversationId)}/messages${query ? `?${query}` : ''}`,
    { cache: 'no-store' },
  );
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as AgentMessageListResult;
}

export async function appendMessage(
  conversationId: string,
  input: {
    role: string;
    text: string;
    reasoning?: string;
    imageIds?: string[];
    taskId?: string;
    proposalData?: AgentProposalData;
    webSearchUsed?: boolean;
    withdrawable?: boolean;
  },
): Promise<ServerMessage> {
  const response = await authFetch(`/api/nova/agent/conversations/${encodeURIComponent(conversationId)}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerMessage;
}

export async function withdrawMessage(conversationId: string, messageId: string): Promise<number> {
  const response = await authFetch(
    `/api/nova/agent/conversations/${encodeURIComponent(conversationId)}/messages/${encodeURIComponent(messageId)}/withdraw`,
    { method: 'POST' },
  );
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { removed?: number };
  return data.removed ?? 0;
}

export async function batchDeleteMessages(conversationId: string, ids: string[]): Promise<number> {
  const response = await authFetch(
    `/api/nova/agent/conversations/${encodeURIComponent(conversationId)}/messages/batch-delete`,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids }) },
  );
  if (!response.ok) throw await readApiError(response);
  const data = (await response.json()) as { deleted?: number };
  return data.deleted ?? 0;
}

// ===== 会话图片目录（assets source_kind='conversation'）=====

export async function uploadAgentImage(
  conversationId: string,
  file: Blob,
  source: 'uploaded' | 'asset' | 'generated',
  description?: string,
  width?: number,
  height?: number,
): Promise<ServerAssetLike> {
  const form = new FormData();
  form.append('file', file);
  form.append('conversationId', conversationId);
  form.append('source', source);
  if (description) form.append('description', description);
  if (width) form.append('width', String(width));
  if (height) form.append('height', String(height));
  const response = await authFetch('/api/nova/agent/images', { method: 'POST', body: form });
  if (!response.ok) throw await readApiError(response);
  return (await response.json()) as ServerAssetLike;
}

export async function updateAgentImageDescription(assetId: string, description: string): Promise<void> {
  const response = await authFetch(`/api/nova/agent/images/${encodeURIComponent(assetId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ description }),
  });
  if (!response.ok) throw await readApiError(response);
}

/** 会话图片对象 URL（鉴权读取）。 */
export function agentImageUrl(assetId: string): string {
  return `/api/nova/agent/images/${encodeURIComponent(assetId)}`;
}

/** 服务端消息 → 前端 AgentMessage（createdAt 转 epoch ms）。 */
export function toAgentMessage(msg: ServerMessage): AgentMessage {
  return {
    id: msg.id,
    role: msg.role,
    text: msg.text,
    reasoning: msg.reasoning ?? undefined,
    imageIds: msg.imageIds ?? [],
    taskId: msg.taskId ?? undefined,
    proposalData: msg.proposalData ?? undefined,
    webSearchUsed: msg.webSearchUsed ?? undefined,
    withdrawable: msg.withdrawable,
    createdAt: new Date(msg.createdAt).getTime(),
  };
}

/** 服务端会话图片 → 前端 AgentImageRecord（imgId=assetId，统一素材引用，ADR-36）。 */
export function toAgentImageRecord(img: ServerConversationImage): AgentImageRecord {
  return {
    imgId: img.assetId,
    source: img.source,
    thumbnail: agentImageUrl(img.assetId),
    description: img.description || '(无描述)',
    mimeType: img.mimeType || 'image/png',
    width: img.width ?? undefined,
    height: img.height ?? undefined,
    createdAt: Date.now(),
  };
}
