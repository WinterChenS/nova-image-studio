'use client';

/**
 * WIN-41 (T11, ADR-44) — 自动上下文压缩分隔条。
 * 当会话存在 context_summary（后端压缩了 N 条较早消息）时渲染：
 * 「已压缩 N 条较早消息」+「查看最早未压缩消息」跳转锚点。
 */

import { useCallback, useState } from 'react';
import { ChevronDown, FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type { ServerContextSummary } from '@/lib/agent-api';

export interface ContextCompressionDividerProps {
  summary: ServerContextSummary;
  /** 返回最早未压缩消息 id（内部负责加载更早分页直到命中）；未命中返回 null */
  onJumpToEarliest: () => Promise<string | null>;
  className?: string;
}

export function ContextCompressionDivider({
  summary,
  onJumpToEarliest,
  className,
}: ContextCompressionDividerProps) {
  const [jumping, setJumping] = useState(false);
  const foldedCount = summary.foldedCount ?? 0;
  const foldedAt = summary.foldedAt ? new Date(summary.foldedAt) : null;
  const label = foldedCount > 0
    ? `已压缩 ${foldedCount} 条较早消息`
    : '较早消息已折叠为摘要';

  const handleJump = useCallback(async () => {
    if (jumping) return;
    setJumping(true);
    try {
      const anchorId = await onJumpToEarliest();
      if (anchorId) {
        // 滚动到最早未压缩消息（AgentMessageBubble 根节点带 data-message-id）
        document
          .querySelector(`[data-message-id="${CSS.escape(anchorId)}"]`)
          ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    } finally {
      setJumping(false);
    }
  }, [jumping, onJumpToEarliest]);

  return (
    <div className={cn('flex items-center gap-2 py-1.5 text-[11px] text-muted-foreground/80', className)}>
      <div className="h-px flex-1 bg-border" />
      <span className="inline-flex items-center gap-1">
        <FileText className="h-3 w-3" />
        {label}
        {foldedAt ? (
          <span className="opacity-70">
            （{foldedAt.toLocaleDateString()} {foldedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}）
          </span>
        ) : null}
      </span>
      {summary.foldedBeforeMessageId && (
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="h-5 gap-1 px-1.5 font-medium text-muted-foreground/90 hover:text-foreground"
          onClick={() => void handleJump()}
          disabled={jumping}
        >
          <ChevronDown className="h-3 w-3" />
          {jumping ? '定位中…' : '查看最早未压缩消息'}
        </Button>
      )}
      <div className="h-px flex-1 bg-border" />
    </div>
  );
}
