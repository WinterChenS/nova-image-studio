'use client';

/**
 * WIN-22 (T13 / F-10..F-21) — 素材管理页（服务端化）。
 * 项目筛选（全部/单项目/未分类）· 来源分类（9 枚举）· 搜索 · 排序 · 分页 ·
 * 批量（删除/移动到项目/客户端 jszip 打包下载，ADR-21）· multipart 上传 ·
 * 新建提示词（text 素材）· 灯箱预览 · 编辑（含移动项目，D13 key 不变）。
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import JSZip from 'jszip';
import {
  Check,
  Download,
  FileText,
  ImagePlus,
  Loader2,
  Pencil,
  Search,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import {
  assetDownloadUrl,
  assetFileUrl,
  assetFileName,
  batchDeleteAssets,
  batchMoveAssets,
  createImageAsset,
  createTextAsset,
  fetchAssets,
  updateAsset,
  type AssetListParams,
  type ServerAsset,
} from '@/lib/assets-api';
import { getSourceKindLabel, formatAssetSize, type AssetSourceKind } from '@/lib/asset-store';
import { useCurrentProject } from '@/hooks/useCurrentProject';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Select } from '@/components/ui/select';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { ConfirmDialog } from '@/components/workspace/dialogs/ConfirmDialog';
import { useToast } from '@/components/console/useToast';
import { cn } from '@/lib/utils';

const SOURCE_KINDS: AssetSourceKind[] = [
  'text-to-image', 'image-to-image', 'agent', 'reverse-prompt', 'gif',
  'upload', 'prompt-gallery', 'manual', 'random',
];

const PAGE_SIZE = 48;

const SORT_OPTIONS: Array<{ value: 'newest' | 'oldest' | 'used'; label: string }> = [
  { value: 'newest', label: '最新添加' },
  { value: 'oldest', label: '最早添加' },
  { value: 'used', label: '最近使用' },
];

interface EditState {
  asset: ServerAsset;
  name: string;
  tags: string;
  note: string;
  projectId: string;
}

function splitTags(input: string): string[] {
  return input.split(/[,\s，、]+/).map(t => t.trim()).filter(Boolean);
}

export function AssetsPanel() {
  const { projects } = useCurrentProject();
  const [assets, setAssets] = useState<ServerAsset[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [params, setParams] = useState<AssetListParams>({ sort: 'newest', page: 1, size: PAGE_SIZE });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<ServerAsset | null>(null);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [moveTarget, setMoveTarget] = useState<string | null>(null);
  const [deleteIds, setDeleteIds] = useState<string[] | null>(null);
  const toast = useToast();

  const projectOptions = useMemo(() => [
    { value: '', label: '全部项目' },
    ...projects.map(p => ({ value: p.id, label: p.name })),
    { value: '__unclassified__', label: '未分类' },
  ], [projects]);

  const load = useCallback(async (next: AssetListParams) => {
    setLoading(true);
    try {
      const result = await fetchAssets(next);
      setAssets(result.items);
      setTotal(result.total);
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async asset list load
    void load(params);
  }, [load, params]);

  const updateParams = useCallback((patch: Partial<AssetListParams>) => {
    setParams(prev => ({ ...prev, ...patch, page: patch.page ?? 1 }));
  }, []);

  const toggleSelect = (id: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  // ===== 批量操作 =====

  const handleBatchDelete = async () => {
    const ids = deleteIds ?? Array.from(selected);
    if (ids.length === 0) return;
    try {
      const deleted = await batchDeleteAssets(ids);
      toast.show(`已删除 ${deleted} 个素材`, 'success');
      setSelected(new Set());
      await load(params);
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
    setDeleteIds(null);
  };

  const handleBatchMove = async () => {
    if (!moveTarget || selected.size === 0) return;
    try {
      const moved = await batchMoveAssets(Array.from(selected), moveTarget);
      toast.show(`已移动 ${moved} 个素材`, 'success');
      setSelected(new Set());
      setMoveTarget(null);
      await load(params);
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  };

  const handleZipDownload = async () => {
    const ids = Array.from(selected);
    if (ids.length === 0) return;
    const zip = new JSZip();
    const readme: string[] = ['Nova Image Studio 素材导出', '', `导出时间: ${new Date().toLocaleString()}`, ''];
    try {
      for (const asset of assets.filter(a => ids.includes(a.id))) {
        if (asset.kind === 'text') {
          zip.file(`${asset.name || asset.id}.txt`, asset.content || asset.prompt || '');
        } else {
          const response = await fetch(assetFileUrl(asset.id), { cache: 'no-store' });
          const blob = await response.blob();
          zip.file(assetFileName(asset), blob);
        }
        readme.push(`- ${asset.name || asset.id}（来源：${getSourceKindLabel(asset.sourceKind)}）`);
      }
      zip.file('README.txt', readme.join('\n'));
      const blob = await zip.generateAsync({ type: 'blob' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `nova-assets-${Date.now()}.zip`;
      a.click();
      URL.revokeObjectURL(url);
      toast.show('打包下载已开始', 'success');
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  };

  // ===== 上传 =====

  const handleUpload = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    setUploading(true);
    const projectId = params.projectId && params.projectId !== '__unclassified__'
      ? params.projectId : (projects[0]?.id ?? '');
    let ok = 0;
    let fail = 0;
    for (const file of Array.from(files)) {
      try {
        await createImageAsset({ file, projectId, sourceKind: 'upload' });
        ok++;
      } catch (e) {
        fail++;
        toast.show(`「${file.name}」上传失败：${e instanceof Error ? e.message : String(e)}`, 'error');
      }
    }
    setUploading(false);
    setUploadOpen(false);
    if (ok > 0) {
      toast.show(`已上传 ${ok} 个素材${fail ? `，失败 ${fail} 个` : ''}`, 'success');
      await load(params);
    }
  };

  const handleCreateText = async () => {
    if (!edit) return;
    const projectId = edit.projectId;
    try {
      await createTextAsset({
        content: edit.note, // 内容暂存于 note 输入框
        projectId,
        name: edit.name,
        tags: splitTags(edit.tags),
        sourceKind: 'manual',
      });
      toast.show('提示词素材已创建', 'success');
      setEdit(null);
      await load(params);
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  };

  const handleEditSave = async () => {
    if (!edit) return;
    try {
      await updateAsset(edit.asset.id, {
        name: edit.name,
        tags: splitTags(edit.tags),
        note: edit.note,
        projectId: edit.projectId,
      });
      toast.show('素材已更新', 'success');
      setEdit(null);
      await load(params);
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / (params.size ?? PAGE_SIZE)));

  return (
    <div className="space-y-4">
      {/* 工具栏 */}
      <div className="flex flex-wrap items-center gap-2">
        <Select
          value={params.projectId ?? ''}
          onValueChange={value => updateParams({ projectId: value })}
          options={projectOptions}
          placeholder="全部项目"
          className="w-40"
        />
        <Select
          value={params.source ?? ''}
          onValueChange={value => updateParams({ source: value })}
          options={[
            { value: '', label: '全部来源' },
            ...SOURCE_KINDS.map(kind => ({ value: kind, label: getSourceKindLabel(kind) })),
          ]}
          className="w-36"
        />
        <div className="relative">
          <Search className="absolute top-1/2 left-2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={params.q ?? ''}
            onChange={e => updateParams({ q: e.target.value })}
            placeholder="搜索名称/备注/来源…"
            className="h-8 w-48 pl-7"
          />
        </div>
        <Select
          value={params.sort ?? 'newest'}
          onValueChange={value => updateParams({ sort: value as AssetListParams['sort'] })}
          options={SORT_OPTIONS}
          className="w-28"
        />
        <div className="ml-auto flex items-center gap-1.5">
          <Button variant="outline" size="sm" onClick={() => setUploadOpen(true)} className="gap-1">
            <Upload className="size-3.5" /> 上传
          </Button>
          <Button
            variant="outline" size="sm"
            onClick={() => { setEdit(null); setEdit({ asset: { id: '', kind: 'text' } as ServerAsset, name: '', tags: '', note: '', projectId: params.projectId && params.projectId !== '__unclassified__' ? params.projectId : (projects[0]?.id ?? '') }); }}
            className="gap-1"
          >
            <FileText className="size-3.5" /> 新建提示词
          </Button>
        </div>
      </div>

      {/* 批量工具条 */}
      {selected.size > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border/70 bg-muted/40 px-3 py-2 text-sm">
          <span className="text-muted-foreground">已选 {selected.size} 项</span>
          <Button variant="outline" size="sm" onClick={handleZipDownload} className="gap-1">
            <Download className="size-3.5" /> 打包下载
          </Button>
          <Select
            value={moveTarget ?? ''}
            onValueChange={setMoveTarget}
            options={[
              { value: '', label: '移动到项目…' },
              ...projects.map(p => ({ value: p.id, label: p.name })),
              { value: '__unclassified__', label: '未分类' },
            ]}
            className="w-36"
          />
          <Button variant="outline" size="sm" onClick={handleBatchMove} disabled={!moveTarget}>移动</Button>
          <Button variant="outline" size="sm" onClick={() => setDeleteIds(Array.from(selected))} className="gap-1 text-destructive">
            <Trash2 className="size-3.5" /> 删除
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())} className="gap-1">
            <X className="size-3.5" /> 取消选择
          </Button>
        </div>
      )}

      {/* 列表 */}
      {loading ? (
        <div className="flex justify-center py-12"><Loader2 className="size-6 animate-spin text-muted-foreground" /></div>
      ) : assets.length === 0 ? (
        <div className="rounded-xl border border-dashed p-10 text-center text-sm text-muted-foreground">
          暂无素材 — 上传图片或新建提示词。
        </div>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {assets.map(asset => (
              <div
                key={asset.id}
                className={cn(
                  'group relative overflow-hidden rounded-xl border border-border/70 bg-card shadow-sm',
                  selected.has(asset.id) && 'border-primary ring-2 ring-primary/40',
                )}
              >
                <button
                  type="button"
                  onClick={() => toggleSelect(asset.id)}
                  className="absolute top-2 left-2 z-10 rounded-md bg-background/80 p-1 hover:bg-background"
                  aria-label="选择"
                >
                  {selected.has(asset.id) ? <Check className="size-3.5 text-primary" /> : null}
                </button>
                {asset.kind === 'text' ? (
                  <button
                    type="button"
                    onClick={() => setPreview(asset)}
                    className="flex h-36 w-full flex-col gap-1 p-3 text-left"
                  >
                    <FileText className="size-5 text-muted-foreground" />
                    <p className="line-clamp-4 text-xs text-muted-foreground">{asset.content || asset.prompt}</p>
                  </button>
                ) : (
                  <img
                    src={assetFileUrl(asset.id)}
                    alt={asset.name || '素材'}
                    loading="lazy"
                    className="h-36 w-full cursor-pointer object-cover"
                    onClick={() => setPreview(asset)}
                  />
                )}
                <div className="flex items-center justify-between gap-1 p-2">
                  <div className="min-w-0">
                    <p className="truncate text-xs font-medium">{asset.name || '未命名'}</p>
                    <div className="mt-0.5 flex items-center gap-1">
                      <Badge variant="secondary" className="text-[10px]">{getSourceKindLabel(asset.sourceKind)}</Badge>
                      {asset.sizeBytes != null && asset.kind === 'image' && (
                        <span className="text-[10px] text-muted-foreground">{formatAssetSize(asset.sizeBytes)}</span>
                      )}
                    </div>
                  </div>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-7 shrink-0"
                    onClick={() => setEdit({
                      asset,
                      name: asset.name ?? '',
                      tags: (asset.tags ?? []).join(', '),
                      note: asset.note ?? '',
                      projectId: asset.projectId ?? '',
                    })}
                    aria-label="编辑"
                  >
                    <Pencil className="size-3.5" />
                  </Button>
                </div>
              </div>
            ))}
          </div>

          {/* 分页 */}
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>共 {total} 个素材</span>
            <div className="flex items-center gap-1">
              <Button variant="outline" size="sm" disabled={(params.page ?? 1) <= 1} onClick={() => updateParams({ page: (params.page ?? 1) - 1 })}>上一页</Button>
              <span className="px-2">{(params.page ?? 1)} / {totalPages}</span>
              <Button variant="outline" size="sm" disabled={(params.page ?? 1) >= totalPages} onClick={() => updateParams({ page: (params.page ?? 1) + 1 })}>下一页</Button>
            </div>
          </div>
        </>
      )}

      {/* 上传对话框 */}
      <Dialog open={uploadOpen} onOpenChange={setUploadOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>上传素材</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <label className="flex cursor-pointer flex-col items-center gap-2 rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground hover:bg-muted/40">
              <ImagePlus className="size-6" />
              点击选择图片（png/jpeg/webp/gif，单文件 ≤ 20MB）
              <input type="file" accept="image/*" multiple className="hidden" onChange={e => { void handleUpload(e.target.files); e.target.value = ''; }} />
            </label>
            {uploading && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="size-4 animate-spin" /> 上传中…
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setUploadOpen(false)}>关闭</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 灯箱预览 */}
      {preview && (
        <Dialog open onOpenChange={open => { if (!open) setPreview(null); }}>
          <DialogContent className="max-w-2xl">
            <DialogHeader>
              <DialogTitle className="pr-6">{preview.name || '素材'}</DialogTitle>
            </DialogHeader>
            {preview.kind === 'text' ? (
              <pre className="max-h-96 overflow-auto rounded-lg bg-muted p-4 text-sm whitespace-pre-wrap">{preview.content || preview.prompt}</pre>
            ) : (
              <img src={assetFileUrl(preview.id)} alt={preview.name || '素材'} className="max-h-[70vh] w-full rounded-lg object-contain" />
            )}
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <Badge variant="secondary">{getSourceKindLabel(preview.sourceKind)}</Badge>
              {preview.tags?.map(tag => <Badge key={tag}>{tag}</Badge>)}
              {preview.note && <span className="truncate">{preview.note}</span>}
              <a href={assetDownloadUrl(preview.id)} download className="ml-auto inline-flex items-center gap-1 text-primary hover:underline">
                <Download className="size-3.5" /> 下载
              </a>
            </div>
          </DialogContent>
        </Dialog>
      )}

      {/* 编辑 / 新建提示词 对话框 */}
      {edit && (
        <Dialog open onOpenChange={open => { if (!open) setEdit(null); }}>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle>{edit.asset.id ? '编辑素材' : '新建提示词'}</DialogTitle>
            </DialogHeader>
            <div className="space-y-3">
              <div>
                <label className="mb-1 block text-xs text-muted-foreground">名称</label>
                <Input value={edit.name} onChange={e => setEdit({ ...edit, name: e.target.value })} placeholder="素材名称" />
              </div>
              <div>
                <label className="mb-1 block text-xs text-muted-foreground">所属项目</label>
                <Select
                  value={edit.projectId}
                  onValueChange={value => setEdit({ ...edit, projectId: value })}
                  options={[
                    ...projects.map(p => ({ value: p.id, label: p.name })),
                    { value: '__unclassified__', label: '未分类' },
                  ]}
                />
              </div>
              {edit.asset.id ? (
                <>
                  <div>
                    <label className="mb-1 block text-xs text-muted-foreground">标签（逗号分隔）</label>
                    <Input value={edit.tags} onChange={e => setEdit({ ...edit, tags: e.target.value })} />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs text-muted-foreground">备注</label>
                    <Textarea value={edit.note} onChange={e => setEdit({ ...edit, note: e.target.value })} rows={2} />
                  </div>
                </>
              ) : (
                <div>
                  <label className="mb-1 block text-xs text-muted-foreground">提示词内容</label>
                  <Textarea value={edit.note} onChange={e => setEdit({ ...edit, note: e.target.value })} rows={4} placeholder="输入提示词内容…" />
                </div>
              )}
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setEdit(null)}>取消</Button>
              <Button onClick={edit.asset.id ? handleEditSave : handleCreateText}>保存</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}

      {deleteIds && (
        <ConfirmDialog
          title="删除素材"
          message={`确定删除选中的 ${deleteIds.length} 个素材？此操作无法撤销。`}
          confirmText="删除"
          onConfirm={() => void handleBatchDelete()}
          onCancel={() => setDeleteIds(null)}
        />
      )}
    </div>
  );
}
