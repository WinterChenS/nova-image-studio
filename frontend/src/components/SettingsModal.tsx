'use client';

/**
 * WIN-25 (T19, A2) + WIN-42 (T17, C10) — 设置弹窗：
 * - 移除模型 Key 编辑区（用户自维护 Key 已下线）；
 * - 移除「数据备份与恢复」Tab（backup-utils 备份/恢复下线，C10：无残留入口）。
 * 保留：默认模型（走全局目录，available 禁用）、关于。
 */

import { useEffect, useMemo, useState } from 'react';
import {
  ExternalLink, Info, Save, Settings,
} from 'lucide-react';
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/select';
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

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async settings load (repo pattern)
    setLoading(true);
    setError(null);
    setSuccess(null);
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

  return (
    <Dialog open={isOpen} onOpenChange={(open) => {
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
