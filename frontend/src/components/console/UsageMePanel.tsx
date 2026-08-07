'use client';

/**
 * WIN-29 (T24, A11) — 我的用量页：用户自助查询本人用量（仅本人数据，后端
 * 恒绑定当前登录用户）。汇总卡片（总费用/总 token/请求数/成功率）+ 日聚合
 * 列表（usage_daily_agg，明细清理后仍在，A10）+ 最近明细分页。权限 usage.me
 * （user 默认角色内置）。
 */

import { useCallback, useEffect, useState } from 'react';
import { Loader2, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { queryUsageMe, type UsageMeResult } from '@/lib/admin-api';

const PAGE_SIZE = 20;

function formatCost(value: unknown): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0';
  return Number(value).toFixed(4);
}

export function UsageMePanel() {
  const [data, setData] = useState<UsageMeResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);

  const load = useCallback(async (nextPage: number) => {
    setLoading(true);
    setError(null);
    try {
      const result = await queryUsageMe({ page: nextPage, size: PAGE_SIZE });
      setData(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载用量失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async load (repo pattern)
    void load(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold tracking-tight">我的用量</h2>
        <Button variant="ghost" size="sm" onClick={() => void load(page)} disabled={loading} className="gap-1.5">
          <RefreshCw className={`size-4 ${loading ? 'animate-spin' : ''}`} />
          刷新
        </Button>
      </div>

      {error && <div className="rounded-xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">{error}</div>}

      {/* 汇总卡片 */}
      {data && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div className="rounded-2xl border border-border/70 bg-card p-4">
            <div className="text-xs text-muted-foreground">总费用（CNY）</div>
            <div className="mt-1 text-xl font-semibold tabular-nums">{formatCost(data.summary.totalCost)}</div>
          </div>
          <div className="rounded-2xl border border-border/70 bg-card p-4">
            <div className="text-xs text-muted-foreground">总 Token</div>
            <div className="mt-1 text-xl font-semibold tabular-nums">{(data.summary.totalTokens ?? 0).toLocaleString('zh-CN')}</div>
          </div>
          <div className="rounded-2xl border border-border/70 bg-card p-4">
            <div className="text-xs text-muted-foreground">请求数</div>
            <div className="mt-1 text-xl font-semibold tabular-nums">{data.summary.requestCount ?? 0}</div>
          </div>
          <div className="rounded-2xl border border-border/70 bg-card p-4">
            <div className="text-xs text-muted-foreground">成功率</div>
            <div className="mt-1 text-xl font-semibold tabular-nums">
              {data.summary.successRate == null ? '—' : `${data.summary.successRate}%`}
            </div>
          </div>
        </div>
      )}

      {/* 日聚合（跨保留期存活） */}
      {data && data.daily.length > 0 && (
        <div className="rounded-2xl border border-border/70 bg-card">
          <div className="px-4 pt-3 text-sm font-medium">按日汇总</div>
          <div className="max-h-64 overflow-y-auto p-2">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="px-2 py-1.5">日期</th>
                  <th className="px-2 py-1.5">类型</th>
                  <th className="px-2 py-1.5 text-right">请求</th>
                  <th className="px-2 py-1.5 text-right">成功</th>
                  <th className="px-2 py-1.5 text-right">Tokens</th>
                  <th className="px-2 py-1.5 text-right">费用</th>
                </tr>
              </thead>
              <tbody>
                {data.daily.map((row, i) => (
                  <tr key={`${row.date}-${row.reqType ?? ''}-${i}`} className="border-t border-border/50">
                    <td className="px-2 py-1.5 tabular-nums">{row.date}</td>
                    <td className="px-2 py-1.5">{row.reqType === 'image' ? '图片' : row.reqType === 'text' ? '文本' : row.reqType}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{row.requestCount}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{row.successCount}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{((row.inputTokens ?? 0) + (row.outputTokens ?? 0)).toLocaleString('zh-CN')}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{formatCost(row.cost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 最近明细 */}
      <div className="rounded-2xl border border-border/70 bg-card">
        <div className="px-4 pt-3 text-sm font-medium">最近明细</div>
        {loading ? (
          <div className="flex items-center justify-center gap-2 p-8 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" /> 加载中…
          </div>
        ) : (
          <div className="overflow-x-auto p-2">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="px-2 py-1.5">时间</th>
                  <th className="px-2 py-1.5">模型</th>
                  <th className="px-2 py-1.5">类型</th>
                  <th className="px-2 py-1.5">状态</th>
                  <th className="px-2 py-1.5 text-right">Tokens</th>
                  <th className="px-2 py-1.5 text-right">费用</th>
                </tr>
              </thead>
              <tbody>
                {(data?.items ?? []).map((item) => (
                  <tr key={item.id} className="border-t border-border/50">
                    <td className="px-2 py-1.5 tabular-nums text-xs text-muted-foreground">
                      {item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN') : ''}
                    </td>
                    <td className="px-2 py-1.5">{item.modelName || item.modelId || '—'}</td>
                    <td className="px-2 py-1.5">{item.reqType === 'image' ? '图片' : item.reqType === 'text' ? '文本' : item.reqType}</td>
                    <td className="px-2 py-1.5">{item.status}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{((item.inputTokens ?? 0) + (item.outputTokens ?? 0)).toLocaleString('zh-CN')}</td>
                    <td className="px-2 py-1.5 text-right tabular-nums">{formatCost(item.cost)}</td>
                  </tr>
                ))}
                {!data || data.items.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-2 py-6 text-center text-sm text-muted-foreground">
                      暂无用量记录
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
        )}
        {data && data.total > data.size && (
          <div className="flex items-center justify-end gap-2 border-t border-border/50 p-2 text-xs">
            <Button variant="ghost" size="sm" disabled={page <= 1} onClick={() => { setPage(page - 1); void load(page - 1); }}>上一页</Button>
            <span className="text-muted-foreground">{page} / {totalPages}</span>
            <Button variant="ghost" size="sm" disabled={page >= totalPages} onClick={() => { setPage(page + 1); void load(page + 1); }}>下一页</Button>
          </div>
        )}
      </div>
    </div>
  );
}
