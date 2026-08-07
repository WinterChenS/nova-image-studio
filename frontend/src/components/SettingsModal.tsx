'use client';

/**
 * WIN-25 (T19, A2) — 设置弹窗：移除模型 Key 编辑区（用户自维护 Key 已下线，
 * 模型由管理员在「账号池管理 → 模型目录」统一配置）。保留：
 * 默认模型（走全局目录，available 禁用）、备份、关于。
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  CheckCircle2, Database, Download, ExternalLink, Info, Save, Settings, Upload, XCircle,
} from 'lucide-react';
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/select';
import { BackupProgress } from '@/components/BackupProgress';
import { exportAllData, importAllData, downloadBlob, generateBackupFilename, type BackupProgress as BackupProgressType } from '@/lib/backup-utils';
import { loadRegistry, type DefaultModels } from '@/lib/nova-models';
import { fetchSettings, saveSettings } from '@/lib/settings-api';
import { PROMPT_DATA_SOURCES, getPromptSourceLabel } from '@/lib/prompt-gallery-data';
import { BA_RANDOM_URL, BING_WALLPAPER_URL } from '@/lib/constants';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
  onApiKeyChange?: (hasKey: boolean) => void;
  isLoggedIn?: boolean;
  onRequireLogin?: () => void;
}

const DEFAULT_DEFAULTS: DefaultModels = {
  textToImage: '',
  imageToImage: '',
  reversePrompt: '',
  agent: '',
  promptOptimize: '',
  imageDescribe: '',
};

export function SettingsModal({ isOpen, onClose }: SettingsModalProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [defaults, setDefaults] = useState<DefaultModels>(DEFAULT_DEFAULTS);
  const [saving, setSaving] = useState(false);

  const [backupProgress, setBackupProgress] = useState<BackupProgressType>({ percent: 0, message: '' });
  const [isBackupActive, setIsBackupActive] = useState(false);
  const [backupError, setBackupError] = useState<string | null>(null);
  const [backupSuccess, setBackupSuccess] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async settings load (repo pattern)
    setLoading(true);
    setError(null);
    setSuccess(null);
    setBackupError(null);
    setBackupSuccess(null);
    Promise.all([fetchSettings().catch((): Record<string, unknown> => ({})), Promise.resolve(loadRegistry())])
      .then(([settings]) => {
        if (cancelled) return;
        const stored = (settings['registry.defaults'] || {}) as Partial<DefaultModels>;
        setDefaults({ ...DEFAULT_DEFAULTS, ...stored });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen]);

  const registry = useMemo(() => loadRegistry(), []);

  const imageOptions = registry.imageModels.map((m) => ({
    value: m.id,
    label: m.available === false ? `${m.name}（无可用账号）` : m.name,
    disabled: m.available === false,
  }));
  const textOptions = registry.textModels.map((m) => ({
    value: m.id,
    label: m.available === false ? `${m.name}（无可用账号）` : m.name,
    disabled: m.available === false,
  }));

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    setSuccess(null);
    try {
      await saveSettings({ 'registry.defaults': defaults });
      window.dispatchEvent(new Event('nova-model-registry-updated'));
      setSuccess('默认模型已保存');
    } catch (err) {
      setError(err instanceof Error ? `保存失败：${err.message}` : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleExport = async () => {
    setIsBackupActive(true);
    setBackupError(null);
    setBackupSuccess(null);
    try {
      const blob = await exportAllData((progress) => setBackupProgress(progress));
      const filename = generateBackupFilename();
      downloadBlob(blob, filename);
      setBackupSuccess(`数据已成功导出为 ${filename}`);
    } catch (err) {
      setBackupError(err instanceof Error ? err.message : '导出失败');
    } finally {
      setIsBackupActive(false);
    }
  };

  const handleImport = async (file: File) => {
    if (!file.name.endsWith('.zip')) {
      setBackupError('请选择有效的备份文件（.zip 格式）');
      return;
    }
    setIsBackupActive(true);
    setBackupError(null);
    setBackupSuccess(null);
    try {
      await importAllData(file, (progress) => setBackupProgress(progress));
      setBackupSuccess('数据已成功导入，页面将在 2 秒后刷新。');
      setTimeout(() => window.location.reload(), 2000);
    } catch (err) {
      setBackupError(err instanceof Error ? err.message : '导入失败');
      setIsBackupActive(false);
    }
  };

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) handleImport(file);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  return (
    <Dialog open={isOpen} onOpenChange={(open) => {
      if (!open && isBackupActive) return;
      if (!open) onClose();
    }}>
      <DialogContent className="flex max-h-[92vh] flex-col overflow-hidden p-0 pt-0 gap-0 sm:max-w-2xl">
        <DialogHeader className="p-4 pb-3">
          <div className="flex items-center gap-2">
            <Settings className="w-5 h-5 text-muted-foreground" />
            <DialogTitle>设置</DialogTitle>
          </div>
          <DialogDescription>
            模型账号由管理员统一配置（账号池）；此处仅设置各工作流的默认模型。
          </DialogDescription>
        </DialogHeader>

        <Tabs defaultValue="defaults" className="min-h-0 flex-1 gap-0">
          <TabsList className="w-full rounded-none border-b bg-transparent h-auto p-0">
            <TabsTrigger value="defaults" className="gap-2 rounded-none border-b-2 border-transparent data-active:border-primary data-active:bg-transparent data-active:shadow-none px-4 py-3">
              <Settings className="w-4 h-4" />
              默认模型
            </TabsTrigger>
            <TabsTrigger value="backup" className="gap-2 rounded-none border-b-2 border-transparent data-active:border-primary data-active:bg-transparent data-active:shadow-none px-4 py-3">
              <Database className="w-4 h-4" />
              备份
            </TabsTrigger>
            <TabsTrigger value="about" className="gap-2 rounded-none border-b-2 border-transparent data-active:border-primary data-active:bg-transparent data-active:shadow-none px-4 py-3">
              <Info className="w-4 h-4" />
              关于
            </TabsTrigger>
          </TabsList>

          <TabsContent value="defaults" className="min-h-0 overflow-y-auto p-4 sm:p-6 space-y-4 mt-0">
            {loading && <div className="rounded-lg bg-muted/50 p-4 text-sm text-muted-foreground">正在加载...</div>}
            {!loading && registry.imageModels.length === 0 && registry.textModels.length === 0 && (
              <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 p-4 text-sm space-y-2">
                <p className="text-amber-700 dark:text-amber-400">
                  模型目录暂无可用模型。请联系管理员在「管理控制台 → 账号池管理 → 模型目录」中配置。
                </p>
              </div>
            )}
            {error && <div className="rounded-lg border border-destructive/20 bg-destructive/10 p-3 text-sm text-destructive">{error}</div>}
            {success && <div className="rounded-lg border border-emerald-500/20 bg-emerald-500/10 p-3 text-sm text-emerald-700 dark:text-emerald-400">{success}</div>}

            <div className="grid gap-3 md:grid-cols-2">
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground">文生图默认模型</label>
                <Select value={defaults.textToImage} onValueChange={(v) => setDefaults((prev) => ({ ...prev, textToImage: v }))} options={imageOptions} placeholder="选择模型" />
              </div>
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground">图生图默认模型</label>
                <Select value={defaults.imageToImage} onValueChange={(v) => setDefaults((prev) => ({ ...prev, imageToImage: v }))} options={imageOptions} placeholder="选择模型" />
              </div>
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground">反推提示词默认模型</label>
                <Select value={defaults.reversePrompt} onValueChange={(v) => setDefaults((prev) => ({ ...prev, reversePrompt: v }))} options={textOptions} placeholder="选择模型" />
              </div>
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground">Agent 默认模型</label>
                <Select value={defaults.agent} onValueChange={(v) => setDefaults((prev) => ({ ...prev, agent: v }))} options={textOptions} placeholder="选择模型" />
              </div>
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground">提示词优化默认模型</label>
                <Select value={defaults.promptOptimize} onValueChange={(v) => setDefaults((prev) => ({ ...prev, promptOptimize: v }))} options={textOptions} placeholder="选择模型" />
              </div>
              <div className="space-y-2">
                <label className="text-xs text-muted-foreground">图片描述默认模型</label>
                <Select value={defaults.imageDescribe} onValueChange={(v) => setDefaults((prev) => ({ ...prev, imageDescribe: v }))} options={textOptions} placeholder="选择模型" />
              </div>
            </div>

            <div className="flex justify-end">
              <Button onClick={() => void handleSave()} disabled={saving || loading} className="gap-2">
                <Save className="w-4 h-4" />
                {saving ? '保存中...' : '保存默认模型'}
              </Button>
            </div>
          </TabsContent>

          <TabsContent value="backup" className="min-h-0 overflow-y-auto p-4 sm:p-6 space-y-6 mt-0">
            <div className="space-y-4">
              <div className="space-y-2">
                <h3 className="text-base font-medium">数据备份与恢复</h3>
                <p className="text-sm text-muted-foreground">导出任务历史、设置与图片为 ZIP 压缩包，或从备份文件恢复数据。</p>
              </div>

              <BackupProgress percent={backupProgress.percent} message={backupProgress.message} isActive={isBackupActive} />

              {backupSuccess && !isBackupActive && (
                <div className="flex items-start gap-3 rounded-lg border border-emerald-200 bg-emerald-50 p-4 dark:border-emerald-800 dark:bg-emerald-950/30">
                  <CheckCircle2 className="w-5 h-5 flex-shrink-0 text-emerald-600 dark:text-emerald-500 mt-0.5" />
                  <p className="text-sm text-emerald-900 dark:text-emerald-100">{backupSuccess}</p>
                </div>
              )}
              {backupError && !isBackupActive && (
                <div className="flex items-start gap-3 rounded-lg border border-destructive/20 bg-destructive/10 p-4">
                  <XCircle className="w-5 h-5 text-destructive flex-shrink-0 mt-0.5" />
                  <p className="text-sm text-destructive break-all">{backupError}</p>
                </div>
              )}

              <div className="space-y-3 rounded-lg border p-4">
                <div className="flex items-start gap-3">
                  <Download className="w-5 h-5 text-muted-foreground mt-0.5" />
                  <div className="flex-1 space-y-2">
                    <h4 className="font-medium">导出数据</h4>
                    <p className="text-sm text-muted-foreground">将所有本地数据打包为 ZIP 文件下载。</p>
                    <Button onClick={() => void handleExport()} disabled={isBackupActive} className="gap-2">
                      <Download className="w-4 h-4" />
                      全量备份
                    </Button>
                  </div>
                </div>
              </div>

              <div className="space-y-3 rounded-lg border p-4">
                <div className="flex items-start gap-3">
                  <Upload className="w-5 h-5 text-muted-foreground mt-0.5" />
                  <div className="flex-1 space-y-2">
                    <h4 className="font-medium">导入数据</h4>
                    <p className="text-sm text-muted-foreground"><span className="font-medium text-destructive">警告：这会覆盖现有数据。</span></p>
                    <input ref={fileInputRef} type="file" accept=".zip" onChange={handleFileSelect} className="hidden" />
                    <Button onClick={() => fileInputRef.current?.click()} disabled={isBackupActive} variant="outline" className="gap-2">
                      <Upload className="w-4 h-4" />
                      选择备份文件
                    </Button>
                  </div>
                </div>
              </div>
            </div>
          </TabsContent>

          <TabsContent value="about" className="min-h-0 overflow-y-auto p-4 sm:p-6 space-y-4 mt-0">
            <div className="space-y-4 text-sm">
              <h3 className="text-lg font-medium">Nova Image <span className="text-xs text-muted-foreground font-normal">v{process.env.NEXT_PUBLIC_APP_VERSION}</span></h3>
              <p className="text-sm text-muted-foreground">
                项目地址：
                {' '}
                <a href="https://github.com/tianjiangqiji/nova-image-studio" target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">
                  tianjiangqiji/nova-image-studio <ExternalLink className="w-3 h-3" />
                </a>
              </p>
              <details className="group rounded-lg bg-muted/50 p-3">
                <summary className="flex cursor-pointer select-none items-center gap-2 font-medium">
                  <span className="text-[10px] opacity-60 transition-transform group-open:rotate-90">▶</span>
                  使用方法
                </summary>
                <ol className="mt-3 list-decimal list-inside space-y-2 text-muted-foreground">
                  <li>模型账号由管理员在「账号池管理」中统一配置。</li>
                  <li>在「默认模型」中为各工作流选择默认模型（无可用账号的模型不可选）。</li>
                  <li>即可开始生图、反推或 Agent 工作流。</li>
                </ol>
              </details>
              <details className="group rounded-lg bg-muted/50 p-3">
                <summary className="flex cursor-pointer select-none items-center gap-2 font-medium">
                  <span className="text-[10px] opacity-60 transition-transform group-open:rotate-90">▶</span>
                  数据来源
                </summary>
                <ul className="mt-3 list-disc list-inside space-y-2 text-muted-foreground">
                  <li>
                    <span className="text-foreground">提示词广场</span> - 提示词来源：
                    <ul className="mt-1 ml-5 list-disc list-inside space-y-1">
                      {PROMPT_DATA_SOURCES.map((source) => (
                        <li key={source.name}>
                          <a href={source.sourceUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">
                            {getPromptSourceLabel(source.sourceUrl)} <ExternalLink className="w-3 h-3" />
                          </a>
                        </li>
                      ))}
                    </ul>
                  </li>
                  <li>
                    <span className="text-foreground">随机图片 · BA人物</span> -{' '}
                    <a href={BA_RANDOM_URL} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">
                      img.catcdn.cn <ExternalLink className="w-3 h-3" />
                    </a>
                  </li>
                  <li>
                    <span className="text-foreground">随机图片 · Bing壁纸</span> -{' '}
                    <a href={BING_WALLPAPER_URL} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">
                      bing.img.run <ExternalLink className="w-3 h-3" />
                    </a>
                  </li>
                </ul>
              </details>
            </div>
          </TabsContent>
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}
