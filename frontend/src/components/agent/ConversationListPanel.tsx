'use client';

/**
 * WIN-39 (WIN-40 T4) — Agent 多会话列表 UI（C1）：新建 / 切换 / 重命名 / 归档 /
 * 软删 / 回收站入口。会话数据来自服务端（/api/nova/agent/conversations，AC-1 跨端一致）。
 */

import { useCallback, useEffect, useState } from 'react';
import { Bot, Check, ChevronDown, MessageSquare, Plus, RefreshCw, RotateCcw, Trash2, Archive } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import {
  createConversation,
  emptyConversationTrash,
  listConversations,
  patchConversation,
  restoreConversation,
  softDeleteConversation,
  type ServerConversation,
} from '@/lib/agent-api';

interface ConversationListPanelProps {
  activeConversationId: string | null;
  onSwitch: (id: string) => void | Promise<void>;
  onNewConversation: (id: string) => void | Promise<void>;
  /** 会话变更后需要外部刷新的信号（如清空重开） */
  refreshToken?: number;
}

export function ConversationListPanel({
  activeConversationId,
  onSwitch,
  onNewConversation,
  refreshToken = 0,
}: ConversationListPanelProps) {
  const [open, setOpen] = useState(false);
  const [conversations, setConversations] = useState<ServerConversation[]>([]);
  const [trash, setTrash] = useState<ServerConversation[]>([]);
  const [loading, setLoading] = useState(false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameText, setRenameText] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [active, deleted] = await Promise.all([
        listConversations({ status: 'active', limit: 100 }),
        listConversations({ status: 'recycle', limit: 100 }),
      ]);
      // active+archived 合并展示；回收站独立分区
      const archived = active.items.filter(c => c.status === 'archived');
      const actives = active.items.filter(c => c.status === 'active');
      setConversations([...actives, ...archived]);
      setTrash(deleted.items);
    } catch {
      // 服务端不可用 → 保留现有列表
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // 延迟到宏任务执行，避免 effect 内同步 setState 级联渲染（react-hooks/set-state-in-effect）
    const timer = setTimeout(() => void load(), 0);
    return () => clearTimeout(timer);
  }, [load, refreshToken]);

  const handleNew = useCallback(async () => {
    try {
      const created = await createConversation();
      setConversations(prev => [created, ...prev]);
      await onNewConversation(created.id);
    } catch {
      // 配额超限等 → 由上层 toast 提示
    }
  }, [onNewConversation]);

  const handleRename = useCallback(async (id: string) => {
    const title = renameText.trim();
    if (!title) { setRenamingId(null); return; }
    setBusyId(id);
    try {
      await patchConversation(id, { title });
      setConversations(prev => prev.map(c => c.id === id ? { ...c, title } : c));
    } finally {
      setBusyId(null);
      setRenamingId(null);
    }
  }, [renameText]);

  const handleArchive = useCallback(async (id: string) => {
    setBusyId(id);
    try {
      await patchConversation(id, { status: 'archived' });
      setConversations(prev => prev.map(c => c.id === id ? { ...c, status: 'archived' } : c));
    } finally {
      setBusyId(null);
    }
  }, []);

  const handleRestoreFromArchive = useCallback(async (id: string) => {
    setBusyId(id);
    try {
      await patchConversation(id, { status: 'active' });
      setConversations(prev => prev.map(c => c.id === id ? { ...c, status: 'active' } : c));
    } finally {
      setBusyId(null);
    }
  }, []);

  const handleSoftDelete = useCallback(async (id: string) => {
    setBusyId(id);
    try {
      await softDeleteConversation(id);
      setConversations(prev => prev.filter(c => c.id !== id));
      setTrash(prev => [prev[0] ? prev[0] : null].filter(Boolean) as ServerConversation[]);
      await load();
    } finally {
      setBusyId(null);
    }
  }, [load]);

  const handleRestore = useCallback(async (id: string) => {
    setBusyId(id);
    try {
      await restoreConversation(id);
      setTrash(prev => prev.filter(c => c.id !== id));
      await load();
    } finally {
      setBusyId(null);
    }
  }, [load]);

  // WIN-42 (T16)：清空回收站（硬删全部 deleted 会话）
  const handleEmptyTrash = useCallback(async () => {
    if (!window.confirm('确定清空回收站？删除的会话将无法恢复。')) return;
    setBusyId('__empty_trash__');
    try {
      const removed = await emptyConversationTrash();
      setTrash([]);
      await load();
      window.dispatchEvent(new CustomEvent('agent-trash-cleared', { detail: { removed } }));
    } finally {
      setBusyId(null);
    }
  }, [load]);

  const activeCount = conversations.filter(c => c.status === 'active').length;

  return (
    <div className="border-b border-border">
      <div className="flex items-center gap-1 px-3 py-1.5">
        <Button
          variant="ghost"
          size="xs"
          className="gap-1 text-muted-foreground"
          onClick={() => setOpen(v => !v)}
          title="会话列表（多会话）"
        >
          <MessageSquare className="h-3.5 w-3.5" />
          会话
          <span className="rounded-full bg-muted px-1.5 text-[10px] leading-4">{activeCount}</span>
          <ChevronDown className={cn('h-3 w-3 transition-transform', open && 'rotate-180')} />
        </Button>
        <Button variant="ghost" size="xs" className="gap-1 text-muted-foreground" onClick={() => void handleNew()} title="新建会话">
          <Plus className="h-3.5 w-3.5" />
          新建
        </Button>
        <Button variant="ghost" size="xs" className="gap-1 text-muted-foreground" onClick={() => void load()} title="刷新列表">
          <RefreshCw className={cn('h-3 w-3', loading && 'animate-spin')} />
        </Button>
        {open && (
          <span className="ml-auto truncate text-[11px] text-muted-foreground">
            {activeConversationId ? '会话已保存至云端，可跨设备恢复' : ''}
          </span>
        )}
      </div>

      {open && (
        <div className="max-h-56 space-y-0.5 overflow-y-auto px-2 pb-2">
          {conversations.length === 0 && trash.length === 0 && (
            <p className="px-2 py-2 text-xs text-muted-foreground">暂无会话，点击「新建」开始。</p>
          )}
          {conversations.map(conv => {
            const active = conv.id === activeConversationId;
            const archived = conv.status === 'archived';
            return (
              <div
                key={conv.id}
                className={cn(
                  'group flex items-center gap-1 rounded-md px-2 py-1.5 text-sm hover:bg-muted/60',
                  active && 'bg-primary/10 hover:bg-primary/10',
                )}
              >
                <button
                  type="button"
                  className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
                  onClick={() => { void onSwitch(conv.id); setOpen(false); }}
                  title={conv.title}
                >
                  {active ? <Check className="h-3 w-3 shrink-0 text-primary" /> : <Bot className="h-3 w-3 shrink-0 text-muted-foreground" />}
                  {renamingId === conv.id ? (
                    <input
                      autoFocus
                      value={renameText}
                      onChange={e => setRenameText(e.target.value)}
                      onBlur={() => void handleRename(conv.id)}
                      onKeyDown={e => { if (e.key === 'Enter') void handleRename(conv.id); if (e.key === 'Escape') setRenamingId(null); }}
                      className="w-full rounded border border-border bg-background px-1 text-xs"
                    />
                  ) : (
                    <span className={cn('truncate text-xs', archived && 'italic text-muted-foreground')}>
                      {conv.title || '未命名会话'}
                    </span>
                  )}
                </button>
                {!renamingId && (
                  <span className="hidden items-center gap-0.5 group-hover:flex">
                    <button
                      type="button"
                      className="rounded p-0.5 text-muted-foreground hover:bg-muted"
                      title="重命名"
                      onClick={() => { setRenamingId(conv.id); setRenameText(conv.title || ''); }}
                    >
                      <Bot className="h-3 w-3" />
                    </button>
                    {archived ? (
                      <button type="button" className="rounded p-0.5 text-muted-foreground hover:bg-muted" title="恢复" onClick={() => void handleRestoreFromArchive(conv.id)}>
                        <RotateCcw className="h-3 w-3" />
                      </button>
                    ) : (
                      <button type="button" className="rounded p-0.5 text-muted-foreground hover:bg-muted" title="归档" onClick={() => void handleArchive(conv.id)}>
                        <Archive className="h-3 w-3" />
                      </button>
                    )}
                    <button type="button" className="rounded p-0.5 text-muted-foreground hover:text-destructive" title="软删（回收站）" onClick={() => void handleSoftDelete(conv.id)}>
                      <Trash2 className="h-3 w-3" />
                    </button>
                  </span>
                )}
                {busyId === conv.id && <RefreshCw className="h-3 w-3 animate-spin text-muted-foreground" />}
              </div>
            );
          })}

          {trash.length > 0 && (
            <>
              <div className="flex items-center justify-between px-2 pt-2">
                <p className="text-[10px] uppercase tracking-wide text-muted-foreground">回收站</p>
                <button
                  type="button"
                  className="rounded p-0.5 text-muted-foreground hover:text-destructive"
                  title="清空回收站（不可恢复）"
                  disabled={busyId === '__empty_trash__'}
                  onClick={() => void handleEmptyTrash()}
                >
                  <Trash2 className={cn('h-3 w-3', busyId === '__empty_trash__' && 'animate-pulse')} />
                </button>
              </div>
              {trash.map(conv => (
                <div key={conv.id} className="group flex items-center gap-1 rounded-md px-2 py-1.5 text-sm hover:bg-muted/60">
                  <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground line-through">{conv.title}</span>
                  <button type="button" className="rounded p-0.5 text-muted-foreground hover:bg-muted" title="恢复" onClick={() => void handleRestore(conv.id)}>
                    <RotateCcw className="h-3 w-3" />
                  </button>
                </div>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}
