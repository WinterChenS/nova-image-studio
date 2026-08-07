'use client';

/**
 * WIN-25 (T21, A9) — 审计与费用页（对齐原型 prototype-win25）：
 * 筛选（时间/用户/模型/账号/协议/类型/状态）+ 汇总卡片（总费用/总 token/
 * 请求数/成功率）+ 分页明细 + CSV 导出按钮（audit.export）。权限 audit.view。
 */

import { useCallback, useEffect, useState } from 'react';
import { Download, FilterX, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select } from '@/components/ui/select';
import {
  exportUsageCsv, queryUsage, type UsageFilters, type UsageItem, type UsageQueryResult,
} from '@/lib/admin-api';
import { usePerm } from '@/lib/permissions';

const PAGE_SIZE = 50;

function formatNumber(value: unknown): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 4 });
}

function formatCost(value: unknown): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0';
  return Number(value).toFixed(4);
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export function AuditPanel() {
  const canExport = usePerm('audit.export');

  const [filters, setFilters] = useState<UsageFilters>({ page: 1, size: PAGE_SIZE });
  const [data, setData] = useState<UsageQueryResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  const load = useCallback(async (next: UsageFilters) => {
    setLoading(true);
    setError(null);
    try {
      const result = await queryUsage(next);
      setData(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载审计数据失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async load (repo pattern)
    void load(filters);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const applyFilters = (patch: Partial<UsageFilters>) => {
    const next = { ...filters, ...patch, page: 1 };
    setFilters(next);
    void load(next);
  };

  const goPage = (page: number) => {
    const next = { ...filters, page };
    setFilters(next);
    void load(next);
  };

  const resetFilters = () => {
    const next: UsageFilters = { page: 1, size: PAGE_SIZE };
    setFilters(next);
    void load(next);
  };

  const handleExport = async () => {
    setExporting(true);
    setError(null);
    try {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { page: _page, size: _size, ...exportFilters } = filters;
      const blob = await exportUsageCsv(exportFilters);
      const stamp = new Date().toISOString().slice(0, 10);
      downloadBlob(blob, `nova-usage-${stamp}.csv`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '导出失败');
    } finally {
      setExporting(false);
    }
  };

  const summary = data?.summary;
  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold">审计与费用</h2>
          <p className="text-xs text-muted-foreground">用量明细（token 消耗 / 费用快照）——保留期默认 180 天可配。</p>
        </div>
        {canExport && (
          <Button variant="outline" size="sm" className="gap-2" onClick={() => void handleExport()} disabled={exporting || !data}>
            {exporting ? <Loader2 className="size-4 animate-spin" /> : <Download className="size-4" />}
            {exporting ? '导出中...' : '导出 CSV'}
          </Button>
        )}
      </div>

      {/* 筛选 */}
      <div className="grid gap-2 rounded-xl border p-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">开始时间</label>
          <Input type="datetime-local" value={filters.from || ''} onChange={(e) => applyFilters({ from: e.target.value ? new Date(e.target.value).toISOString() : undefined })} />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">结束时间</label>
          <Input type="datetime-local" value={filters.to || ''} onChange={(e) => applyFilters({ to: e.target.value ? new Date(e.target.value).toISOString() : undefined })} />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">用户</label>
          <Input value={filters.userId || ''} onChange={(e) => applyFilters({ userId: e.target.value })} placeholder="用户 ID（留空全部）" />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">模型</label>
          <Input value={filters.modelId || ''} onChange={(e) => applyFilters({ modelId: e.target.value })} placeholder="目录模型 ID（留空全部）" />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">账号</label>
          <Input value={filters.accountId || ''} onChange={(e) => applyFilters({ accountId: e.target.value })} placeholder="账号 ID（留空全部）" />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">协议</label>
          <Input value={filters.protocol || ''} onChange={(e) => applyFilters({ protocol: e.target.value })} placeholder="如 openai / google" />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">类型</label>
          <Select value={filters.reqType || ''} onValueChange={(v) => applyFilters({ reqType: v || undefined })} options={[
            { value: '', label: '全部' },
            { value: 'image', label: '图片' },
            { value: 'text', label: '文本' },
          ]} />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">状态</label>
          <Select value={filters.status || ''} onValueChange={(v) => applyFilters({ status: v || undefined })} options={[
            { value: '', label: '全部' },
            { value: 'success', label: '成功' },
            { value: 'retried', label: '换号重试' },
            { value: 'failed', label: '失败' },
          ]} />
        </div>
      </div>

      <div className="flex items-center justify-end">
        <Button variant="ghost" size="sm" className="gap-1.5 text-muted-foreground" onClick={resetFilters}>
          <FilterX className="size-3.5" />重置筛选
        </Button>
      </div>

      {error && <div className="rounded-lg border border-destructive/20 bg-destructive/10 p-3 text-sm text-destructive">{error}</div>}

      {/* 汇总卡片 */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <div className="rounded-xl border p-4">
          <p className="text-xs text-muted-foreground">总费用</p>
          <p className="mt-1 text-lg font-semibold">{formatCost(summary?.totalCost)} <span className="text-xs text-muted-foreground">{data?.items?.[0]?.currency || 'CNY'}</span></p>
        </div>
        <div className="rounded-xl border p-4">
          <p className="text-xs text-muted-foreground">总 token</p>
          <p className="mt-1 text-lg font-semibold">{formatNumber(summary?.totalTokens)}</p>
        </div>
        <div className="rounded-xl border p-4">
          <p className="text-xs text-muted-foreground">请求数</p>
          <p className="mt-1 text-lg font-semibold">{formatNumber(summary?.requestCount)}</p>
        </div>
        <div className="rounded-xl border p-4">
          <p className="text-xs text-muted-foreground">成功率</p>
          <p className="mt-1 text-lg font-semibold">{summary?.successRate === null || summary?.successRate === undefined ? '—' : `${summary.successRate}%`}</p>
        </div>
      </div>

      {/* 明细 */}
      <div className="overflow-x-auto rounded-xl border">
        <table className="w-full min-w-[860px] text-sm">
          <thead>
            <tr className="border-b bg-muted/50 text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-medium">时间</th>
              <th className="px-3 py-2 font-medium">用户</th>
              <th className="px-3 py-2 font-medium">模型</th>
              <th className="px-3 py-2 font-medium">账号</th>
              <th className="px-3 py-2 font-medium">类型</th>
              <th className="px-3 py-2 font-medium">tokens(入/出)</th>
              <th className="px-3 py-2 font-medium">图数</th>
              <th className="px-3 py-2 font-medium">费用</th>
              <th className="px-3 py-2 font-medium">状态</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr><td colSpan={9} className="px-3 py-8 text-center text-muted-foreground"><Loader2 className="mx-auto size-5 animate-spin" /></td></tr>
            )}
            {!loading && (data?.items?.length || 0) === 0 && (
              <tr><td colSpan={9} className="px-3 py-8 text-center text-sm text-muted-foreground">暂无审计记录</td></tr>
            )}
            {!loading && data?.items?.map((item: UsageItem) => (
              <tr key={item.id} className="border-b last:border-0">
                <td className="whitespace-nowrap px-3 py-2 text-xs text-muted-foreground">{item.createdAt ? item.createdAt.slice(0, 19).replace('T', ' ') : '—'}</td>
                <td className="px-3 py-2">{item.username || item.userId?.slice(0, 8) || '—'}</td>
                <td className="max-w-[160px] truncate px-3 py-2">{item.modelName || '—'}</td>
                <td className="max-w-[140px] truncate px-3 py-2">{item.accountName || '—'}</td>
                <td className="px-3 py-2 text-xs">{item.reqType === 'image' ? '图片' : item.reqType === 'text' ? '文本' : (item.reqType || '—')}</td>
                <td className="px-3 py-2 text-xs">{formatNumber(item.inputTokens)}/{formatNumber(item.outputTokens)}</td>
                <td className="px-3 py-2 text-xs">{item.images ?? '—'}</td>
                <td className="px-3 py-2">{formatCost(item.cost)}</td>
                <td className="px-3 py-2">
                  <span className={`text-xs ${item.status === 'success' ? 'text-emerald-600' : item.status === 'retried' ? 'text-amber-600' : 'text-destructive'}`}>
                    {item.status === 'success' ? '成功' : item.status === 'retried' ? '重试' : (item.status || '—')}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* 分页 */}
      <div className="flex items-center justify-between text-sm">
        <span className="text-xs text-muted-foreground">共 {formatNumber(data?.total)} 条 · 第 {data?.page || 1}/{totalPages} 页</span>
        <div className="flex items-center gap-1">
          <Button variant="outline" size="sm" disabled={!data || data.page <= 1 || loading} onClick={() => goPage((data?.page || 1) - 1)}>上一页</Button>
          <Button variant="outline" size="sm" disabled={!data || (data?.page || 1) >= totalPages || loading} onClick={() => goPage((data?.page || 1) + 1)}>下一页</Button>
        </div>
      </div>
    </div>
  );
}
