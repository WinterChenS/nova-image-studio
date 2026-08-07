'use client';

/**
 * WIN-22 (T15 / F-35, P1) — 设置页（管理控制台）：存储状态卡（MinIO 健康/模式/
 * 降级徽标，A12/A13）、默认项目选择、自动收藏开关、迁移入口（文档指引）。
 * 工作台 SettingsModal（模型/Key）保留，双面共存（Q5）。
 */

import { useCallback, useEffect, useState } from 'react';
import { Database, HardDrive, RefreshCw } from 'lucide-react';
import { fetchStorageHealth, type StorageHealth } from '@/lib/assets-api';
import { fetchProjects, type ProjectDto } from '@/lib/projects-api';
import { Select } from '@/components/ui/select';
import { saveSetting } from '@/lib/settings-api';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { useToast } from '@/components/console/useToast';

export function SettingsPanel() {
  const [health, setHealth] = useState<StorageHealth | null>(null);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [defaultProjectId, setDefaultProjectId] = useState('');
  const toast = useToast();

  const load = useCallback(async () => {
    try {
      setHealth(await fetchStorageHealth());
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
    try {
      const list = await fetchProjects(true);
      setProjects(list);
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  }, [toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async storage health load
    void load();
  }, [load]);

  const modeLabel = health?.mode === 'minio' ? 'MinIO 对象存储' : '磁盘存储';
  const degraded = Boolean(health?.fallbackActive);

  return (
    <div className="max-w-xl space-y-4">
      {/* 存储状态卡（F-35 / A12 / A13） */}
      <div className="rounded-xl border border-border/70 bg-card p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            {health?.mode === 'minio'
              ? <Database className="size-4 text-primary" />
              : <HardDrive className="size-4 text-muted-foreground" />}
            <h3 className="text-sm font-semibold">对象存储</h3>
          </div>
          <Button variant="ghost" size="sm" onClick={() => void load()} className="gap-1">
            <RefreshCw className="size-3.5" /> 刷新
          </Button>
        </div>
        <div className="mt-3 space-y-1.5 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">当前模式</span>
            <span className="font-medium">{modeLabel}</span>
          </div>
          {health?.mode === 'minio' && (
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Bucket</span>
              <span className="font-mono text-xs">{health.bucket}</span>
            </div>
          )}
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">MinIO 已配置</span>
            <span>{health?.minioConfigured ? '是' : '否'}</span>
          </div>
          {health?.mode === 'minio' && (
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">连通性</span>
              <Badge variant={health.reachable ? 'secondary' : 'destructive'}>
                {health.reachable ? '正常' : '不可达'}
              </Badge>
            </div>
          )}
        </div>
        {degraded && (
          <p className="mt-3 rounded-lg bg-warning/10 px-3 py-2 text-xs text-warning">
            ⚠ 降级中：MinIO 不可达，素材读写已自动回退磁盘；恢复后请运行迁移脚本补传。
          </p>
        )}
      </div>

      {/* 默认项目 */}
      <div className="rounded-xl border border-border/70 bg-card p-4 shadow-sm">
        <h3 className="text-sm font-semibold">默认项目</h3>
        <p className="mt-1 text-xs text-muted-foreground">
          新素材/新任务缺省归属的项目（工作台顶栏切换可随时更改）。
        </p>
        <div className="mt-3 flex items-center gap-2">
          <Select
            value={defaultProjectId}
            onValueChange={setDefaultProjectId}
            options={projects.map(p => ({ value: p.id, label: p.name }))}
            placeholder="选择默认项目"
            className="w-56"
          />
          <Button
            size="sm"
            disabled={!defaultProjectId}
            onClick={async () => {
              try {
                await saveSetting('workbench.defaultProjectId', defaultProjectId);
                toast.show('默认项目已保存', 'success');
              } catch (e) {
                toast.show(e instanceof Error ? e.message : String(e), 'error');
              }
            }}
          >
            保存
          </Button>
        </div>
      </div>

      {/* 存量迁移 */}
      <div className="rounded-xl border border-border/70 bg-card p-4 shadow-sm">
        <h3 className="text-sm font-semibold">存量迁移</h3>
        <p className="mt-1 text-xs text-muted-foreground">
          将磁盘历史任务图片迁移到 MinIO（幂等可重跑）。执行方式：
        </p>
        <pre className="mt-2 overflow-x-auto rounded-lg bg-muted p-3 text-xs">
          {`cd backend-spring\nbash scripts/migrate-disk-to-minio.sh`}
        </pre>
        <p className="mt-2 text-xs text-muted-foreground">
          详见 <code>docs/WIN22-minio-migration-guide.md</code>。IndexedDB 存量素材导入向导（P1）见素材管理页。
        </p>
      </div>
    </div>
  );
}
