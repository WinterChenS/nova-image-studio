// Agent 模式上下文持久化层 —— WIN-39（WIN-40 T4）由 IndexedDB（nova-agent-db）切换为服务端 API。
// 接口签名与旧版一致（减少 useAgentChat 改动）；会话/消息/pending/图片目录以服务端为唯一数据源（FR-1.1）。
// 会话图片：上传字节经 /api/nova/agent/images → assets（source_kind='conversation'），
// 前端 imgId 即 assetId（统一素材引用，ADR-36）；图片字节经鉴权 URL / 对象读取。
// 本地 IndexedDB 读取能力保留在 backup-utils（迁移工具 T7 使用）。

import type { AgentMessage, AgentImageRecord, AgentProposal } from '@/lib/agent-chat-config';
import type { GptImageBackground, GptImageQuality, GptImageStyle } from '@/lib/model-capabilities';
import {
  appendMessage,
  batchDeleteMessages,
  createConversation,
  getConversation,
  listMessages,
  patchConversation,
  softDeleteConversation,
  toAgentImageRecord,
  toAgentMessage,
  updateAgentImageDescription,
  uploadAgentImage,
  type ServerConversation,
  type ServerPending,
} from '@/lib/agent-api';
import { deleteAsset } from '@/lib/assets-api';

// ===== 当前会话（多会话：切换页面恢复，C1/ADR-38）=====

let activeConversationId: string | null = null;

export function setActiveConversation(id: string | null): void {
  activeConversationId = id;
}

export function getActiveConversation(): string | null {
  return activeConversationId;
}

/**
 * 确保存在当前会话：无 active 时新建（进入 Agent 模式默认加载最近会话，无会话则新建）。
 */
export async function ensureAgentConversation(): Promise<string> {
  if (activeConversationId) return activeConversationId;
  const { listConversations } = await import('@/lib/agent-api');
  const page = await listConversations({ status: 'active', limit: 1 });
  if (page.items.length > 0) {
    activeConversationId = page.items[0].id;
    return activeConversationId;
  }
  const created = await createConversation();
  activeConversationId = created.id;
  return activeConversationId;
}

// ===== 加载完整会话 =====

export interface AgentSessionSnapshot {
  messages: AgentMessage[];
  images: AgentImageRecord[];
  imageModel: string | null;
  conversationId: string | null;
  title: string | null;
}

export async function loadAgentSession(): Promise<AgentSessionSnapshot> {
  const conversationId = activeConversationId;
  if (!conversationId) return { messages: [], images: [], imageModel: null, conversationId: null, title: null };
  try {
    const detail = await getConversation(conversationId);
    const page = await listMessages(conversationId, { limit: 50 });   // 首屏最近 50 条（懒加载 FR-1.3）
    const messages = page.items.map(toAgentMessage).sort((a, b) => a.createdAt - b.createdAt);
    const images = (detail.images ?? []).map(toAgentImageRecord);
    return {
      messages,
      images,
      imageModel: detail.imageModel,
      conversationId: detail.id,
      title: detail.title,
    };
  } catch {
    // 会话已删除/不存在 → 空态
    return { messages: [], images: [], imageModel: null, conversationId, title: null };
  }
}

/** 懒加载更早消息（滚动加载，before 游标）。返回是否还有更早数据。 */
export async function loadEarlierMessages(before: string, limit = 50): Promise<{ messages: AgentMessage[]; nextBefore: string | null }> {
  const conversationId = activeConversationId;
  if (!conversationId) return { messages: [], nextBefore: null };
  const page = await listMessages(conversationId, { before, limit });
  return {
    messages: page.items.map(toAgentMessage),
    nextBefore: page.nextBefore,
  };
}

// ===== 消息读写 =====

export async function putMessage(message: AgentMessage): Promise<void> {
  const conversationId = activeConversationId;
  if (!conversationId) return;
  await appendMessage(conversationId, {
    role: message.role,
    text: message.text,
    reasoning: message.reasoning,
    imageIds: message.imageIds,
    taskId: message.taskId,
    proposalData: message.proposalData,
    webSearchUsed: message.webSearchUsed,
    withdrawable: message.withdrawable,
  });
}

// ===== 图片登记表读写（服务端 assets 目录，imgId=assetId）=====

export async function putImageRecord(record: AgentImageRecord): Promise<void> {
  // 登记元数据（description 等）更新到 assets.extra（ADR-36）
  if (record.imgId && record.description) {
    try {
      await updateAgentImageDescription(record.imgId, record.description);
    } catch {
      // 描述更新失败不阻塞（尽力而为）
    }
  }
}

export async function getAgentImageRecord(imgId: string): Promise<AgentImageRecord | null> {
  const conversationId = activeConversationId;
  if (!conversationId || !imgId) return null;
  try {
    const detail = await getConversation(conversationId);
    const found = (detail.images ?? []).find(img => img.assetId === imgId);
    return found ? toAgentImageRecord(found) : null;
  } catch {
    return null;
  }
}

/** 图片字节读取（经鉴权对象 URL），imgId=assetId。 */
export async function getAgentImageBytes(imgId: string): Promise<Blob | null> {
  if (!imgId) return null;
  try {
    const response = await fetch(`/api/nova/agent/images/${encodeURIComponent(imgId)}`, {
      credentials: 'include',
      headers: await authHeaders(),
    });
    if (!response.ok) return null;
    return await response.blob();
  } catch {
    return null;
  }
}

/** 图片字节 → base64（不含 data: 前缀），供生图上游引用。 */
export async function getAgentImageBase64(imgId: string): Promise<{ data: string; mimeType: string } | null> {
  const blob = await getAgentImageBytes(imgId);
  if (!blob) return null;
  const dataUrl = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
  const base64 = dataUrl.includes(',') ? dataUrl.split(',')[1] : dataUrl;
  return { data: base64, mimeType: blob.type || 'image/png' };
}

/** 上传图片字节到 assets（返回 assetId）；调用方应将其作为 record.imgId。 */
export async function storeAgentImageBytes(imgId: string, blob: Blob, source: AgentImageRecord['source'] = 'uploaded'): Promise<string> {
  const conversationId = activeConversationId;
  if (!conversationId) throw new Error('当前无会话');
  const asset = await uploadAgentImage(conversationId, blob, source);
  return asset.assetId ?? asset.id;
}

// ===== 元信息 =====

export async function saveImageModel(model: string): Promise<void> {
  const conversationId = activeConversationId;
  if (!conversationId) return;
  await patchConversation(conversationId, { imageModel: model });
}

// ===== 撤回 / 删除消息 =====

export async function deleteMessages(ids: string[]): Promise<void> {
  const conversationId = activeConversationId;
  if (!conversationId || ids.length === 0) return;
  // 优先服务端撤回语义（withdrawable），否则批量删除
  await batchDeleteMessages(conversationId, ids);
}

/** 从会话目录移除图片（assets 软删，回收站可恢复，ADR-42）。 */
export async function deleteImageRecords(imgIds: string[]): Promise<void> {
  if (imgIds.length === 0) return;
  await Promise.all(imgIds.map(id => deleteAsset(id).catch(() => {})));
}

/** 图片字节删除（软删后对象保留至清理任务，此处无需额外操作）。 */
export async function deleteAgentImageBytes(_imgId: string): Promise<void> {
  // assets 软删语义：对象由每日清理任务按保留期硬删（ADR-42）；_imgId 保留签名兼容
  void _imgId;
}

// ===== 清空会话（清空重开：软删旧会话 + 新建）=====

export async function clearAgentSession(): Promise<string | null> {
  const conversationId = activeConversationId;
  if (conversationId) {
    try {
      await softDeleteConversation(conversationId);
    } catch {
      // 尽力软删
    }
  }
  const created = await createConversation();
  activeConversationId = created.id;
  return created.id;
}

// ===== Pending Proposal 持久化（FR-1.2 中断恢复，落 conversations.pending）=====

export interface PendingProposalData {
  proposal: AgentProposal;
  pendingAnalysis: string;
  pendingReasoning: string;
  isReedit: boolean;
}

export async function savePendingProposal(data: PendingProposalData): Promise<void> {
  const conversationId = activeConversationId;
  if (!conversationId) return;
  await patchConversation(conversationId, { pending: { kind: 'proposal', ...data } as unknown as ServerPending });
}

export async function loadPendingProposal(): Promise<PendingProposalData | null> {
  const conversationId = activeConversationId;
  if (!conversationId) return null;
  try {
    const detail = await getConversation(conversationId);
    if (!detail.pending || detail.pending.kind !== 'proposal') return null;
    const p = detail.pending as unknown as PendingProposalData;
    if (!p.proposal) return null;
    return { proposal: p.proposal, pendingAnalysis: p.pendingAnalysis, pendingReasoning: p.pendingReasoning, isReedit: !!p.isReedit };
  } catch {
    return null;
  }
}

export async function clearPendingProposal(): Promise<void> {
  const conversationId = activeConversationId;
  if (!conversationId) return;
  await patchConversation(conversationId, { pending: null }).catch(() => {});
}

// ===== Pending Generation 持久化（FR-1.2 中断恢复，落 conversations.pending）=====

export interface PendingGenerationData {
  taskId: string;
  proposal: AgentProposal;
  pendingAnalysis: string;
  pendingReasoning: string;
  selectedImageIds: string[];
  model: string;
  outputSize: string;
  customSize?: string;
  aspectRatio: string;
  temperature: number;
  gptImageQuality?: GptImageQuality;
  gptImageStyle?: GptImageStyle;
  gptImageBackground?: GptImageBackground;
  parallelCount: number;
  startedAt: number;
}

export async function savePendingGeneration(data: PendingGenerationData): Promise<void> {
  const conversationId = activeConversationId;
  if (!conversationId) return;
  await patchConversation(conversationId, { pending: { kind: 'generation', ...data } as unknown as ServerPending });
}

export async function loadPendingGeneration(): Promise<PendingGenerationData | null> {
  const conversationId = activeConversationId;
  if (!conversationId) return null;
  try {
    const detail = await getConversation(conversationId);
    if (!detail.pending || detail.pending.kind !== 'generation') return null;
    return detail.pending as unknown as PendingGenerationData;
  } catch {
    return null;
  }
}

export async function clearPendingGeneration(): Promise<void> {
  const conversationId = activeConversationId;
  if (!conversationId) return;
  await patchConversation(conversationId, { pending: null }).catch(() => {});
}

// ===== 会话级工具 =====

/** 会话详情（含 context_summary，压缩分隔条用，ADR-44）。 */
export async function getConversationDetail(conversationId: string): Promise<ServerConversation | null> {
  try {
    return await getConversation(conversationId);
  } catch {
    return null;
  }
}

/** 重命名会话。 */
export async function renameConversation(conversationId: string, title: string): Promise<void> {
  await patchConversation(conversationId, { title });
}

/** 归档会话。 */
export async function archiveConversation(conversationId: string): Promise<void> {
  await patchConversation(conversationId, { status: 'archived' });
}

/** 软删会话（回收站）。 */
export async function removeConversation(conversationId: string): Promise<void> {
  await softDeleteConversation(conversationId);
}

/** 恢复回收站会话。 */
export async function restoreConversationFromTrash(conversationId: string): Promise<void> {
  const { restoreConversation } = await import('@/lib/agent-api');
  await restoreConversation(conversationId);
}

async function authHeaders(): Promise<Record<string, string>> {
  const { getAuthHeaders } = await import('@/lib/auth');
  return getAuthHeaders();
}

export type { ServerConversation };
