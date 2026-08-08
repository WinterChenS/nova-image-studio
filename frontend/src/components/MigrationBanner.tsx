'use client';

/**
 * WIN-39 (WIN-40 T7) — 迁移提示条：登录后检测本地存量（Agent 会话 / 画布），
 * 有存量时展示迁移入口；运行中显示进度；失败可重试（FR-7.4 轻提示，不阻塞使用）。
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { CloudUpload, Loader2, RefreshCw, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { isLoggedIn } from '@/lib/auth';
import {
  hasLocalAgentData,
  hasLocalCanvasData,
  hasLocalGifData,
  hasLocalReverseData,
  isFeatureMigrated,
  runAgentMigration,
  runCanvasMigration,
  runGifMigration,
  runReverseMigration,
  type MigrationFeature,
} from '@/lib/migration';

interface MigrationBannerProps {
  /** 该入口检测的功能（Agent 会话 / 画布） */
  features: MigrationFeature[];
  onMigrated?: (feature: MigrationFeature) => void;
}

export function MigrationBanner({ features, onMigrated }: MigrationBannerProps) {
  const [pending, setPending] = useState<MigrationFeature[]>([]);
  const [running, setRunning] = useState(false);
  const [done, setDone] = useState(false);
  const [percent, setPercent] = useState(0);
  const [message, setMessage] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState(false);
  const checkedRef = useRef(false);

  useEffect(() => {
    if (!isLoggedIn() || checkedRef.current) return;
    checkedRef.current = true;
    const run = async () => {
      const found: MigrationFeature[] = [];
      for (const feature of features) {
        if (isFeatureMigrated(feature)) continue;
        const has = feature === 'agent' ? await hasLocalAgentData()
          : feature === 'canvas' ? await hasLocalCanvasData()
            : feature === 'reverse' ? await hasLocalReverseData()
              : feature === 'gif' ? hasLocalGifData()
                : false;
        if (has) found.push(feature);
      }
      setPending(found);
    };
    void run();
  }, [features]);

  const runAll = useCallback(async () => {
    setRunning(true);
    setError(null);
    setDone(false);
    const todo = [...pending];
    const progress = (p: number, m: string) => { setPercent(p); setMessage(m); };
    try {
      for (const feature of todo) {
        if (feature === 'agent') {
          await runAgentMigration(progress);
        } else if (feature === 'canvas') {
          await runCanvasMigration(progress);
        } else if (feature === 'reverse') {
          await runReverseMigration(progress);
        } else if (feature === 'gif') {
          await runGifMigration(progress);
        }
        setPending(prev => prev.filter(f => f !== feature));
        onMigrated?.(feature);
      }
      // BUG-4 修复：成功提示由独立 done 态承载，先置 done 再清 pending ——
      // 避免 pending 清空后横幅提前 return null 导致「历史数据已同步到云端」不可见
      setPercent(100);
      setMessage('历史数据已同步到云端');
      setDone(true);
      setPending([]);
      setTimeout(() => setDismissed(true), 3000);
    } catch (e) {
      setError(e instanceof Error ? e.message : '迁移失败，可稍后重试（本地数据已保留）');
      setPercent(0);
    } finally {
      setRunning(false);
    }
  }, [pending, onMigrated]);

  if (dismissed || !isLoggedIn()) return null;
  if (!running && !done && pending.length === 0) return null;

  const label = done ? null
    : pending.includes('agent') && pending.includes('canvas')
      ? '检测到本地 Agent 会话与画布历史数据'
      : pending.includes('agent') ? '检测到本地 Agent 会话历史数据'
        : pending.includes('reverse') && pending.includes('gif') ? '检测到本地反推与 GIF 历史数据'
          : pending.includes('reverse') ? '检测到本地反推历史数据'
            : pending.includes('gif') ? '检测到本地 GIF 任务数据'
              : '检测到本地画布历史数据';

  return (
    <div className="flex items-center gap-2 border-b border-border bg-primary/5 px-4 py-2 text-xs">
      <CloudUpload className="h-3.5 w-3.5 shrink-0 text-primary" />
      <div className="min-w-0 flex-1">
        {done ? (
          <span className="text-primary">{message}</span>
        ) : (
          <>
            <span className="text-muted-foreground">{label}，可一键同步到云端（幂等，可重试）：</span>
            {running && (
              <span className="ml-2 text-primary">
                {message || '迁移中...'}
                {percent > 0 && percent < 100 && `（${percent}%）`}
              </span>
            )}
            {error && <span className="ml-2 text-destructive">{error}</span>}
          </>
        )}
      </div>
      <Button variant="outline" size="xs" disabled={running || done} onClick={() => void runAll()}>
        {running ? <Loader2 className="h-3 w-3 animate-spin" /> : <RefreshCw className="h-3 w-3" />}
        {running ? '迁移中' : done ? '已完成' : error ? '重试' : '开始迁移'}
      </Button>
      {!running && (
        <button type="button" className="rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => setDismissed(true)} title="稍后再说（数据保留在本地）">
          <X className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}
