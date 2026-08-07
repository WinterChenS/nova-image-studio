'use client';

/**
 * WIN-29 (T24, A11) — 我的用量页：用户自助查询本人用量（汇总卡片 + 分页明细 +
 * 日聚合趋势）。数据来自 {@code GET /api/nova/usage/me}（后端恒绑定当前登录
 * 用户，用户间隔离；日趋势来自 usage_daily_agg，明细清理后仍在）。入口：
 * 工作台账号菜单「我的用量」（usage.me 权限，默认角色均有）。
 */

import { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useAppShell } from '@/components/console/AppShell';
import { fetchMyUsage, type MyUsageResult } from '@/lib/admin-api';

const PAGE_SIZE = 20;

function fmt(value: unknown, digits = 4): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: digits });
}

function cost(value: unknown): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0';
  return Number(value).toFixed(4);
}

export function MyUsagePanel() {
  const { exitUsage } = useAppShell();
  const [data, setData] = useState<MyUsageResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);

  const load = useCallback((nextPage: number) => {
    setLoading(true);
    setError(null);
    fetchMyUsage({ page: nextPage, size: PAGE_SIZE })
      .then(setData)
      .catch(err => setError(err instanceof Error ? err.message : '加载我的用量失败'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async load (repo pattern)
    void load(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const summary = data?.summary;
  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-6xl flex-col gap-4 px-3 py-3 sm:px-6 sm:py-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={exitUsage} className="gap-1.5">
            <ArrowLeft className="size-4" />
            返回工作台
          </Button>
          <h1 className="text-lg font-semibold tracking-tight">我的用量</h1>
        </div>
        {data && <span className="text-xs text-muted-foreground">仅展示本人用量（隔离）</span>}
      </div>

      {error && (
        <div className="rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {error}
        </div>
      )}

      {/* 汇总卡片 */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <SummaryCard label="请求数" value={summary ? fmt(summary.requestCount, 0) : '—'} />
        <SummaryCard label="总费用 (CNY)" value={summary ? cost(summary.totalCost) : '—'} />
        <SummaryCard label="总 Token" value={summary ? fmt(summary.totalTokens, 0) : '—'} />
        <SummaryCard label="成功率" value={summary && summary.successRate != null ? `${summary.successRate}%` : '—'} />
      </div>

      {/* 日聚合趋势（usage_daily_agg；明细清理后仍在 A10） */}
      {data && data.daily.length > 0 && (
        <div className="rounded-xl border bg-card p-4">
          <h2 className="mb-2 text-sm font-semibold">近 30 天用量（日聚合）</h2>
          <div className="grid max-h-56 gap-1 overflow-y-auto pr-1 text-xs sm:grid-cols-2 lg:grid-cols-3">
            {data.daily.map((d, i) => (
              <div key={`${d.aggDate}-${d.reqType}-${i}`} className="flex items-center justify-between rounded-lg bg-muted/50 px-3 py-1.5">
                <span className="text-muted-foreground">{d.aggDate}{d.reqType === 'image' ? ' · 生图' : ' · 文本'}</span>
                <span className="font-medium">{cost(d.cost)} CNY / {fmt(d.requestCount, 0)} 次</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 明细 */}
      <div className="rounded-xl border bg-card">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <h2 className="text-sm font-semibold">请求明细</h2>
          {loading && <Loader2 className="size-4 animate-spin text-muted-foreground" />}
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-muted-foreground">
              <tr className="border-b">
                <th className="px-4 py-2 font-medium">时间</th>
                <th className="px-4 py-2 font-medium">模型</th>
                <th className="px-4 py-2 font-medium">账号</th>
                <th className="px-4 py-2 font-medium">类型</th>
                <th className="px-4 py-2 font-medium">Token</th>
                <th className="px-4 py-2 font-medium">费用</th>
                <th className="px-4 py-2 font-medium">状态</th>
              </tr>
            </thead>
            <tbody>
              {(data?.items ?? []).map(item => (
                <tr key={item.id} className="border-b last:border-0 hover:bg-muted/40">
                  <td className="px-4 py-2 whitespace-nowrap">
                    {item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN', { hour12: false }) : '—'}
                  </td>
                  <td className="px-4 py-2">{item.modelName || item.modelId || '—'}</td>
                  <td className="px-4 py-2">{item.accountName || item.accountId || '—'}</td>
                  <td className="px-4 py-2">{item.reqType === 'image' ? '生图' : '文本'}</td>
                  <td className="px-4 py-2">{fmt(item.inputTokens, 0)} / {fmt(item.outputTokens, 0)}</td>
                  <td className="px-4 py-2">{cost(item.cost)}</td>
                  <td className="px-4 py-2">
                    <span className={
                      item.status === 'success' ? 'text-green-600'
                        : item.status === 'retried' ? 'text-amber-600'
                          : 'text-destructive'
                    }>
                      {item.status === 'success' ? '成功' : item.status === 'retried' ? '重试成功' : '失败'}
                    </span>
                  </td>
                </tr>
              ))}
              {data && data.items.length === 0 && (
                <tr><td colSpan={7} className="px-4 py-8 text-center text-muted-foreground">暂无用量记录</td></tr>
              )}
            </tbody>
          </table>
        </div>
        {totalPages > 1 && (
          <div className="flex items-center justify-end gap-2 border-t px-4 py-2">
            <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => { setPage(page - 1); void load(page - 1); }}>
              上一页
            </Button>
            <span className="text-xs text-muted-foreground">{page} / {totalPages}</span>
            <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => { setPage(page + 1); void load(page + 1); }}>
              下一页
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}

function SummaryCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border bg-card p-4">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 text-lg font-semibold tracking-tight">{value}</div>
    </div>
  );
}
