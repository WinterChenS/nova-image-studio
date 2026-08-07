'use client';

/**
 * WIN-22 (T12 / F-1..F-8) — 项目管理页：列表/统计卡片、新建、重命名、描述、
 * 归档·恢复、删除确认（force 提示）、默认项目标识 + 设为默认（A1/A4）。
 */

import { useCallback, useEffect, useState } from 'react';
import { Archive, ArchiveRestore, Folder, Plus, Star, Trash2 } from 'lucide-react';
import {
  createProject,
  deleteProject,
  fetchProjects,
  setDefaultProject,
  updateProject,
  type ProjectDto,
} from '@/lib/projects-api';
import { authFetch, readApiError } from '@/lib/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Select } from '@/components/ui/select';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { ConfirmDialog } from '@/components/workspace/dialogs/ConfirmDialog';
import { useToast } from '@/components/console/useToast';

interface EditState {
  mode: 'create' | 'rename';
  project?: ProjectDto;
}

interface UnclassifiedTask {
  id: string;
  status?: string;
  mode?: string;
  createdAt?: string;
}

/** 未分类任务（A3）：历史无项目任务一键归入指定项目。 */
function UnclassifiedTasksSection({ projects, refresh }: { projects: ProjectDto[]; refresh: () => Promise<void> }) {
  const [tasks, setTasks] = useState<UnclassifiedTask[]>([]);
  const [assignTarget, setAssignTarget] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
  const toast = useToast();

  const load = useCallback(async () => {
    try {
      const response = await authFetch('/api/nova/tasks?projectId=__unclassified__&size=50', { cache: 'no-store' });
      if (!response.ok) throw await readApiError(response);
      const data = (await response.json()) as { items?: UnclassifiedTask[] };
      setTasks(data.items ?? []);
    } catch {
      setTasks([]); // 未登录/接口不可用时静默
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async unclassified tasks load
    void load();
  }, [load]);

  if (tasks.length === 0) return null;

  const assign = async (taskId: string) => {
    const projectId = assignTarget[taskId];
    if (!projectId) return;
    setBusyId(taskId);
    try {
      const response = await authFetch(`/api/nova/tasks/${encodeURIComponent(taskId)}/project`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectId }),
      });
      if (!response.ok) throw await readApiError(response);
      toast.show('任务已归入项目', 'success');
      await load();
      await refresh();
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="rounded-xl border border-dashed border-border/70 bg-card/60 p-4">
      <h3 className="text-sm font-semibold">未分类任务（{tasks.length}）</h3>
      <p className="mt-1 text-xs text-muted-foreground">历史无项目任务显示为「未分类」，可一键归入指定项目（A3）。</p>
      <div className="mt-3 max-h-56 space-y-2 overflow-y-auto pr-1">
        {tasks.map(task => (
          <div key={task.id} className="flex items-center gap-2 rounded-lg border border-border/50 px-2.5 py-1.5 text-xs">
            <span className="min-w-0 flex-1 truncate font-mono">{task.id.slice(0, 18)}</span>
            <span className="shrink-0 text-muted-foreground">{task.status}</span>
            <Select
              value={assignTarget[task.id] ?? ''}
              onValueChange={value => setAssignTarget(prev => ({ ...prev, [task.id]: value }))}
              options={projects.map(p => ({ value: p.id, label: p.name }))}
              placeholder="归入项目…"
              className="w-32"
              size="sm"
            />
            <Button size="sm" disabled={!assignTarget[task.id] || busyId === task.id} onClick={() => void assign(task.id)}>
              {busyId === task.id ? '归入中…' : '归入'}
            </Button>
          </div>
        ))}
      </div>
    </div>
  );
}

export function ProjectsPanel() {
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [includeArchived, setIncludeArchived] = useState(false);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<ProjectDto | null>(null);
  const toast = useToast();

  const refresh = useCallback(async () => {
    try {
      setProjects(await fetchProjects(includeArchived));
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setLoading(false);
    }
  }, [includeArchived, toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async project list load
    void refresh();
  }, [refresh]);

  const openCreate = () => {
    setEdit({ mode: 'create' });
    setName('');
    setDescription('');
  };

  const openRename = (project: ProjectDto) => {
    setEdit({ mode: 'rename', project });
    setName(project.name);
    setDescription(project.description ?? '');
  };

  const handleSave = async () => {
    if (!edit) return;
    if (!name.trim()) {
      toast.show('项目名称不能为空', 'error');
      return;
    }
    try {
      if (edit.mode === 'create') {
        await createProject({ name: name.trim(), description: description.trim() || undefined });
        toast.show('项目已创建', 'success');
      } else if (edit.project) {
        await updateProject(edit.project.id, { name: name.trim(), description: description.trim() || undefined });
        toast.show('项目已更新', 'success');
      }
      setEdit(null);
      await refresh();
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  };

  const handleArchive = async (project: ProjectDto, archived: boolean) => {
    try {
      await updateProject(project.id, { archived });
      toast.show(archived ? '已归档' : '已恢复', 'success');
      await refresh();
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  };

  const handleSetDefault = async (project: ProjectDto) => {
    try {
      await setDefaultProject(project.id);
      toast.show(`「${project.name}」已设为默认项目`, 'success');
      await refresh();
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteProject(deleteTarget.id, false);
      toast.show('项目已删除', 'success');
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      // 409：项目内有素材 —— 二次确认强制删除
      if (message.includes('素材')) {
        const force = window.confirm(`${message}\n\n强制删除将同时删除项目内全部素材（不可恢复）。确定？`);
        if (force) {
          try {
            await deleteProject(deleteTarget.id, true);
            toast.show('项目已强制删除（含素材）', 'success');
          } catch (e2) {
            toast.show(e2 instanceof Error ? e2.message : String(e2), 'error');
          }
        }
      } else {
        toast.show(message, 'error');
      }
    }
    setDeleteTarget(null);
    await refresh();
  };

  if (loading) {
    return <div className="py-12 text-center text-sm text-muted-foreground">加载中…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Button size="sm" onClick={openCreate} className="gap-1.5">
            <Plus className="size-4" /> 新建项目
          </Button>
          <label className="flex cursor-pointer items-center gap-1.5 text-xs text-muted-foreground">
            <input
              type="checkbox"
              checked={includeArchived}
              onChange={e => setIncludeArchived(e.target.checked)}
              className="size-3.5"
            />
            显示已归档
          </label>
        </div>
        <span className="text-xs text-muted-foreground">{projects.length} 个项目</span>
      </div>

      {projects.length === 0 && (
        <div className="rounded-xl border border-dashed p-10 text-center text-sm text-muted-foreground">
          暂无项目 — 新建一个项目来组织素材与生成任务。
        </div>
      )}

      <UnclassifiedTasksSection projects={projects} refresh={refresh} />

      <div className="grid gap-3 sm:grid-cols-2">
        {projects.map(project => (
          <div key={project.id} className="rounded-xl border border-border/70 bg-card p-4 shadow-sm">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <Folder className="size-4 shrink-0 text-muted-foreground" />
                  <h3 className="truncate text-sm font-semibold">{project.name}</h3>
                  {project.autoSave && <Badge variant="secondary">自动收藏</Badge>}
                  {project.archived && <Badge>已归档</Badge>}
                </div>
                {project.description && (
                  <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{project.description}</p>
                )}
              </div>
              <Star
                className="size-4 shrink-0 cursor-pointer text-muted-foreground hover:text-yellow-500"
                onClick={() => handleSetDefault(project)}
                aria-label="设为默认项目"
              />
            </div>

            <div className="mt-3 flex items-center gap-3 text-xs text-muted-foreground">
              <span>任务 {project.taskCount}</span>
              <span>素材 {project.assetCount}</span>
              <span className="truncate">最近活动 {project.lastActivityAt ? new Date(project.lastActivityAt).toLocaleString() : '—'}</span>
            </div>

            <div className="mt-3 flex flex-wrap gap-1.5">
              <Button variant="outline" size="sm" onClick={() => openRename(project)}>重命名</Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => handleArchive(project, !project.archived)}
                className="gap-1"
              >
                {project.archived ? <ArchiveRestore className="size-3.5" /> : <Archive className="size-3.5" />}
                {project.archived ? '恢复' : '归档'}
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setDeleteTarget(project)}
                className="gap-1 text-destructive"
              >
                <Trash2 className="size-3.5" /> 删除
              </Button>
            </div>
          </div>
        ))}
      </div>

      {edit && (
        <Dialog open onOpenChange={open => { if (!open) setEdit(null); }}>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle>{edit.mode === 'create' ? '新建项目' : '重命名项目'}</DialogTitle>
            </DialogHeader>
            <div className="space-y-3">
              <div>
                <label className="mb-1 block text-xs text-muted-foreground">名称（必填）</label>
                <Input value={name} onChange={e => setName(e.target.value)} placeholder="例如：海报组" autoFocus />
              </div>
              <div>
                <label className="mb-1 block text-xs text-muted-foreground">描述（可选）</label>
                <Textarea value={description} onChange={e => setDescription(e.target.value)} rows={2} />
              </div>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setEdit(null)}>取消</Button>
              <Button onClick={handleSave}>{edit.mode === 'create' ? '创建' : '保存'}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}

      {deleteTarget && (
        <ConfirmDialog
          title={`删除项目「${deleteTarget.name}」`}
          message="删除后项目内素材会被一并移除（或提示先迁移）。此操作无法撤销。"
          confirmText="删除"
          onConfirm={handleDelete}
          onCancel={() => setDeleteTarget(null)}
        />
      )}
    </div>
  );
}
