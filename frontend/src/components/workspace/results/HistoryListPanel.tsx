'use client';

/**
 * WIN-41 (T12/T13, C5/C6) — 反推/GIF 历史结果列表。
 * 读统一历史 API（histories 表）：类型分页 + 查看（GIF 网格/成品图预览）+ 删除。
 * 空态/加载态/删除确认；删除后回调 onDeleted 供父级刷新本地槽位。
 */

import { useCallback, useEffect, useState } from 'react';
import { History, ImageIcon, Loader2, RefreshCw, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import {
  deleteHistory,
  historyImageUrl,
  listHistories,
  type HistoryRow,
} from '@/lib/histories-api';

export interface HistoryListPanelProps {
  type: 'reverse' | 'gif';
  /** 本地槽位刷新钩子（反推双槽/GIF 当前 job 变化后由父级传入） */
  refreshKey?: number | string;
  onDeleted?: (row: HistoryRow) => void;
  className?: string;
}

const STATUS_LABEL: Record<string, string> = {
  completed: '已完成',
  draft: '草稿',
  idle: '待开始',
  generating_grid: '网格生成中',
  review_grid: '待编码',
  generating_gif: '编码中',
  done: '已完成',
  failed: '失败',
};

function statusBadge(status: string): { label: string; className: string } {
  const label = STATUS_LABEL[status] ?? status;
  if (status === 'failed') return { label, className: 'bg-destructive/10 text-destructive' };
  if (status === 'generating_grid' || status === 'generating_gif') {
    return { label, className: 'bg-primary/10 text-primary' };
  }
  return { label, className: 'bg-muted text-muted-foreground' };
}

export function HistoryListPanel({ type, refreshKey, onDeleted, className }: HistoryListPanelProps) {
  const [items, setItems] = useState<HistoryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [nextBefore, setNextBefore] = useState<string | null>(null);
  const [preview, setPreview] = useState<HistoryRow | null>(null);
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null);

  const load = useCallback(async (append = false) => {
    setLoading(true);
    setError(null);
    try {
      const page = await listHistories(type, append && nextBefore ? { before: nextBefore } : {});
      setItems(prev => append ? [...prev, ...page.items] : page.items);
      setNextBefore(page.nextBefore);
    } catch (err) {
      setError(err instanceof Error ? err.message : '历史加载失败');
    } finally {
      setLoading(false);
    }
  }, [type, nextBefore]);

  useEffect(() => {
    // 延迟到微任务避免 effect 内同步 setState（react-hooks/immutability）
    queueMicrotask(() => { void load(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, refreshKey]);

  const handleDelete = useCallback(async (row: HistoryRow) => {
    setDeleteConfirmId(null);
    try {
      await deleteHistory(row.id);
      setItems(prev => prev.filter(item => item.id !== row.id));
      onDeleted?.(row);
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    }
  }, [onDeleted]);

  const previewImages = preview?.imageIds ?? [];

  return (
    <div className={cn('space-y-2', className)}>
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
          <History className="h-3.5 w-3.5" />
          {type === 'reverse' ? '反推历史' : 'GIF 历史'}
          {items.length > 0 && <span className="text-muted-foreground/70">（{items.length}）</span>}
        </span>
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="gap-1 text-muted-foreground"
          onClick={() => void load(false)}
          disabled={loading}
          title="刷新"
        >
          <RefreshCw className={cn('h-3 w-3', loading && 'animate-spin')} />
        </Button>
      </div>

      {error && <p className="text-xs text-destructive">{error}</p>}

      {loading && items.length === 0 ? (
        <div className="flex items-center justify-center gap-2 py-4 text-xs text-muted-foreground">
          <Loader2 className="h-3.5 w-3.5 animate-spin" /> 加载中…
        </div>
      ) : items.length === 0 ? (
        <p className="py-3 text-center text-xs text-muted-foreground/70">
          {type === 'reverse' ? '暂无反推历史' : '暂无 GIF 历史'}
        </p>
      ) : (
        <ul className="space-y-1.5">
          {items.map(item => {
            const badge = statusBadge(item.status);
            return (
              <li key={item.id} className="group flex items-center gap-2 rounded-lg border border-border/70 bg-card/50 px-2.5 py-2">
                <button
                  type="button"
                  className="min-w-0 flex-1 text-left"
                  onClick={() => setPreview(item)}
                  title="查看详情"
                >
                  <span className="block truncate text-xs font-medium text-foreground">{item.title || item.id}</span>
                  <span className="mt-0.5 flex items-center gap-1.5 text-[10px] text-muted-foreground">
                    <span className={cn('rounded-full px-1.5 py-px', badge.className)}>{badge.label}</span>
                    <span>{new Date(item.createdAt).toLocaleString([], {
                      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
                    })}</span>
                  </span>
                </button>
                {item.imageIds.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setPreview(item)}
                    className="shrink-0"
                    title="查看图片"
                  >
                    <ImageIcon className="h-3.5 w-3.5 text-muted-foreground" />
                  </button>
                )}
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6 shrink-0 text-muted-foreground opacity-0 group-hover:opacity-100 hover:text-destructive"
                  onClick={() => setDeleteConfirmId(item.id)}
                  title="删除"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </li>
            );
          })}
        </ul>
      )}

      {nextBefore && (
        <div className="flex justify-center">
          <Button variant="ghost" size="xs" className="text-muted-foreground" onClick={() => void load(true)} disabled={loading}>
            {loading ? <Loader2 className="h-3 w-3 animate-spin" /> : '加载更早'}
          </Button>
        </div>
      )}

      {/* 删除确认 */}
      <Dialog open={deleteConfirmId !== null} onOpenChange={(open) => { if (!open) setDeleteConfirmId(null); }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle className="text-sm">删除这条历史记录？</DialogTitle>
          </DialogHeader>
          <p className="text-xs text-muted-foreground">
            {type === 'gif' ? '将同时删除关联的网格/成品图片（可在回收站恢复）。' : '删除后不可恢复。'}
          </p>
          <div className="mt-3 flex justify-end gap-2">
            <Button variant="outline" size="xs" onClick={() => setDeleteConfirmId(null)}>取消</Button>
            <Button
              variant="destructive"
              size="xs"
              onClick={() => {
                const target = items.find(item => item.id === deleteConfirmId);
                if (target) void handleDelete(target);
              }}
            >
              删除
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      {/* 详情预览 */}
      <Dialog open={preview !== null} onOpenChange={(open) => { if (!open) setPreview(null); }}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle className="text-sm">{preview?.title ?? ''}</DialogTitle>
          </DialogHeader>
          {preview && (
            <div className="space-y-3">
              {previewImages.length > 0 && (
                <div className="grid grid-cols-3 gap-2">
                  {previewImages.map(assetId => (
                    <img
                      key={assetId}
                      src={historyImageUrl(assetId)}
                      alt="历史图片"
                      className="aspect-square w-full rounded-md border border-border object-cover"
                    />
                  ))}
                </div>
              )}
              {preview.type === 'reverse' && (
                <p className="whitespace-pre-wrap break-words rounded-md bg-muted/40 px-3 py-2 text-xs leading-relaxed">
                  {String(preview.payload?.text ?? '')}
                </p>
              )}
              {preview.error && <p className="text-xs text-destructive">{preview.error}</p>}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
