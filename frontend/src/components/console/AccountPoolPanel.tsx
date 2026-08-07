'use client';

/**
 * WIN-25 (T20, A1/A4/A8) — 账号池管理页（对齐原型 prototype-win25：
 * 账号列表/表单/测试/健康徽标/恢复 + 模型目录 + 价格配置 Tab）。
 * 权限：account.manage（账号）、model.catalog.manage（目录）、pricing.manage（价格），
 * 按钮级：测试连通 account.test。普通用户无入口（TABS 权限化，T23）。
 */

import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  KeyRound,
  Loader2,
  Pause,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  Trash2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
  accountAction, createAccount, createCatalogModel, deleteAccount, deleteCatalogModel,
  deletePricing, fetchAccounts, fetchCatalogAdmin, fetchPricing, testAccount,
  updateAccount, updateCatalogModel, upsertPricing,
  type AdminAccount, type AdminCatalogModel, type PricingRow,
} from '@/lib/admin-api';
import { usePerm } from '@/lib/permissions';

const PROTOCOL_OPTIONS = [
  { value: 'openai', label: 'OpenAI' },
  { value: 'google', label: 'Google' },
  { value: 'grok', label: 'Grok' },
  { value: 'anthropic', label: 'Anthropic' },
  { value: 'openai-responses', label: 'OpenAI Responses' },
  { value: 'openai-chat-completions', label: 'OpenAI Chat Completions' },
  { value: 'google-gemini', label: 'Google Gemini' },
  { value: 'anthropic-messages', label: 'Anthropic Messages' },
];

function statusBadge(status?: string): { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' } {
  switch (status) {
    case 'active': return { label: '正常', variant: 'default' };
    case 'paused': return { label: '已暂停', variant: 'secondary' };
    case 'broken': return { label: '故障', variant: 'destructive' };
    case 'deleted': return { label: '已删除', variant: 'outline' };
    default: return { label: status || '未知', variant: 'outline' };
  }
}

export function AccountPoolPanel() {
  const canManage = usePerm('account.manage');
  const canTest = usePerm('account.test');
  const canCatalog = usePerm('model.catalog.manage');
  const canPricing = usePerm('pricing.manage');

  const [accounts, setAccounts] = useState<AdminAccount[]>([]);
  const [models, setModels] = useState<AdminCatalogModel[]>([]);
  const [pricing, setPricing] = useState<PricingRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 账号表单
  const [editing, setEditing] = useState<AdminAccount | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<Record<string, string>>({});
  const [testing, setTesting] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<Record<string, { ok: boolean; message: string }>>({});

  // 目录表单
  const [modelFormOpen, setModelFormOpen] = useState(false);
  const [editingModel, setEditingModel] = useState<AdminCatalogModel | null>(null);
  const [modelForm, setModelForm] = useState<Record<string, string>>({});
  const [modelEnabled, setModelEnabled] = useState(true);

  // 价格表单
  const [priceFormOpen, setPriceFormOpen] = useState(false);
  const [priceForm, setPriceForm] = useState<Record<string, string>>({});

  const refresh = async () => {
    setLoading(true);
    setError(null);
    try {
      const [acc, mod, pri] = await Promise.all([fetchAccounts(), fetchCatalogAdmin(), fetchPricing()]);
      setAccounts(acc);
      setModels(mod);
      setPricing(pri);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载账号池失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const modelOptions = useMemo(() => models.map((m) => ({ value: m.id, label: `${m.name}（${m.type === 'image' ? '图片' : '文本'}）` })), [models]);

  // ===== 账号操作 =====

  const openCreate = () => {
    setEditing(null);
    setForm({ name: '', protocol: 'openai', baseUrl: '', apiKey: '', priority: '100', remark: '' });
    setFormOpen(true);
  };

  const openEdit = (account: AdminAccount) => {
    setEditing(account);
    setForm({
      name: account.name,
      protocol: account.protocol,
      baseUrl: account.baseUrl,
      apiKey: '', // 掩码 Key 空 = 沿用已存密文
      priority: String(account.priority ?? 100),
      remark: account.remark || '',
      modelScope: (account.modelScope || []).join(','),
    });
    setFormOpen(true);
  };

  const handleSaveAccount = async () => {
    setBusy(true);
    setError(null);
    try {
      const dto: Record<string, unknown> = {
        name: form.name,
        protocol: form.protocol,
        baseUrl: form.baseUrl,
        priority: Number(form.priority || 100),
        remark: form.remark || '',
      };
      if (form.apiKey) dto.apiKey = form.apiKey;
      if (form.modelScope) dto.modelScope = form.modelScope.split(',').map((s) => s.trim()).filter(Boolean);
      if (editing) {
        await updateAccount(editing.id, dto);
      } else {
        await createAccount(dto);
      }
      setFormOpen(false);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存账号失败');
    } finally {
      setBusy(false);
    }
  };

  const handleAction = async (id: string, action: 'pause' | 'resume' | 'recover') => {
    setBusy(true);
    setError(null);
    try {
      await accountAction(id, action);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async (account: AdminAccount) => {
    if (!window.confirm(`确认删除账号「${account.name}」？删除后不再参与调度（软删除，ADR-28）。`)) return;
    setBusy(true);
    setError(null);
    try {
      await deleteAccount(account.id);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  };

  const handleTest = async (id: string) => {
    setTesting(id);
    setError(null);
    try {
      const result = await testAccount(id);
      setTestResult((prev) => ({ ...prev, [id]: { ok: result.ok, message: result.message || (result.ok ? '连通正常' : '连通失败') } }));
      await refresh(); // 成功清失败计数
    } catch (err) {
      setTestResult((prev) => ({ ...prev, [id]: { ok: false, message: err instanceof Error ? err.message : '测试失败' } }));
    } finally {
      setTesting(null);
    }
  };

  // ===== 目录操作 =====

  const openModelCreate = () => {
    setEditingModel(null);
    setModelForm({ type: 'image', protocol: 'google', name: '', modelId: '', baseUrl: '' });
    setModelEnabled(true);
    setModelFormOpen(true);
  };

  const openModelEdit = (model: AdminCatalogModel) => {
    setEditingModel(model);
    setModelForm({
      type: model.type,
      protocol: model.protocol,
      name: model.name,
      modelId: model.modelId,
      baseUrl: model.baseUrl,
    });
    setModelEnabled(model.enabled);
    setModelFormOpen(true);
  };

  const handleSaveModel = async () => {
    setBusy(true);
    setError(null);
    try {
      const dto: Record<string, unknown> = {
        type: modelForm.type,
        protocol: modelForm.protocol,
        name: modelForm.name,
        modelId: modelForm.modelId,
        baseUrl: modelForm.baseUrl,
        enabled: modelEnabled,
      };
      if (editingModel) {
        await updateCatalogModel(editingModel.id, dto);
      } else {
        await createCatalogModel(dto);
      }
      setModelFormOpen(false);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存模型失败');
    } finally {
      setBusy(false);
    }
  };

  const handleDeleteModel = async (model: AdminCatalogModel) => {
    if (!window.confirm(`确认删除目录模型「${model.name}」？关联价格将级联删除，历史 usage 外键置空（ADR-28）。`)) return;
    setBusy(true);
    setError(null);
    try {
      await deleteCatalogModel(model.id);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  };

  // ===== 价格操作 =====

  const openPriceCreate = () => {
    setPriceForm({ modelId: '', currency: 'CNY', perRequestPrice: '0', pricePerToken: '0' });
    setPriceFormOpen(true);
  };

  const openPriceEdit = (row: PricingRow) => {
    setPriceForm({
      modelId: row.modelId,
      currency: row.currency,
      perRequestPrice: String(row.perRequestPrice ?? 0),
      pricePerToken: String(row.pricePerToken ?? 0),
    });
    setPriceFormOpen(true);
  };

  const handleSavePrice = async () => {
    setBusy(true);
    setError(null);
    try {
      await upsertPricing({
        modelId: priceForm.modelId,
        currency: priceForm.currency || 'CNY',
        perRequestPrice: Number(priceForm.perRequestPrice || 0),
        pricePerToken: Number(priceForm.pricePerToken || 0),
      });
      setPriceFormOpen(false);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存价格失败');
    } finally {
      setBusy(false);
    }
  };

  const handleDeletePrice = async (row: PricingRow) => {
    if (!window.confirm(`确认删除模型「${row.modelName}」的 ${row.currency} 价格配置？`)) return;
    setBusy(true);
    setError(null);
    try {
      await deletePricing(row.modelId, row.currency);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold">账号池管理</h2>
          <p className="text-xs text-muted-foreground">模型账号统一管理：调度选号（最少在飞 + 冷却剔除 + broken 人工恢复）。</p>
        </div>
        <Button variant="outline" size="sm" className="gap-2" onClick={() => void refresh()} disabled={loading}>
          <RefreshCw className={`size-4 ${loading ? 'animate-spin' : ''}`} />
          刷新
        </Button>
      </div>

      {error && <div className="rounded-lg border border-destructive/20 bg-destructive/10 p-3 text-sm text-destructive">{error}</div>}

      <Tabs defaultValue="accounts" className="gap-0">
        <TabsList className="w-full rounded-none border-b bg-transparent h-auto p-0">
          <TabsTrigger value="accounts" className="gap-2 rounded-none border-b-2 border-transparent data-active:border-primary data-active:bg-transparent data-active:shadow-none px-4 py-2.5">账号</TabsTrigger>
          <TabsTrigger value="models" className="gap-2 rounded-none border-b-2 border-transparent data-active:border-primary data-active:bg-transparent data-active:shadow-none px-4 py-2.5">模型目录</TabsTrigger>
          <TabsTrigger value="pricing" className="gap-2 rounded-none border-b-2 border-transparent data-active:border-primary data-active:bg-transparent data-active:shadow-none px-4 py-2.5">价格配置</TabsTrigger>
        </TabsList>

        <TabsContent value="accounts" className="mt-4 space-y-3">
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">共 {accounts.length} 个账号</p>
            {canManage && (
              <Button size="sm" className="gap-2" onClick={openCreate}>
                <Plus className="size-4" />
                新增账号
              </Button>
            )}
          </div>
          {accounts.length === 0 && !loading && (
            <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">暂无账号，点击「新增账号」配置第一个模型账号。</div>
          )}
          <div className="space-y-2">
            {accounts.map((account) => {
              const badge = statusBadge(account.status);
              const broken = account.status === 'broken';
              const failures = account.health?.consecutiveFailures || 0;
              const test = testResult[account.id];
              return (
                <div key={account.id} className="rounded-xl border p-3 space-y-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="font-medium truncate">{account.name}</span>
                      <Badge variant={badge.variant}>{badge.label}</Badge>
                      {broken && (
                        <span className="inline-flex items-center gap-1 text-xs text-destructive">
                          <AlertTriangle className="size-3" />连续失败 {failures} 次
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-1">
                      {canTest && (
                        <Button variant="outline" size="sm" className="gap-1.5" disabled={testing === account.id || account.status === 'deleted'} onClick={() => void handleTest(account.id)}>
                          {testing === account.id ? <Loader2 className="size-3.5 animate-spin" /> : <KeyRound className="size-3.5" />}
                          测试
                        </Button>
                      )}
                      {canManage && (
                        <>
                          {account.status === 'active' && (
                            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => void handleAction(account.id, 'pause')}>
                              <Pause className="size-3.5" />暂停
                            </Button>
                          )}
                          {account.status === 'paused' && (
                            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => void handleAction(account.id, 'resume')}>
                              <Play className="size-3.5" />启用
                            </Button>
                          )}
                          {broken && (
                            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => void handleAction(account.id, 'recover')}>
                              <RotateCcw className="size-3.5" />恢复
                            </Button>
                          )}
                          <Button variant="outline" size="sm" onClick={() => openEdit(account)}>编辑</Button>
                          {account.status !== 'deleted' && (
                            <Button variant="outline" size="sm" className="gap-1.5 text-destructive hover:text-destructive" onClick={() => void handleDelete(account)}>
                              <Trash2 className="size-3.5" />删除
                            </Button>
                          )}
                        </>
                      )}
                    </div>
                  </div>
                  <div className="grid gap-x-6 gap-y-1 text-xs text-muted-foreground sm:grid-cols-2 lg:grid-cols-3">
                    <span>协议：{account.protocol}</span>
                    <span className="truncate">Base URL：{account.baseUrl}</span>
                    <span className="truncate">Key：{account.apiKey || '（未配置）'}</span>
                    <span className="truncate">模型作用域：{account.modelScope?.length ? account.modelScope.length + ' 个' : '全部'}</span>
                    <span>优先级：{account.priority ?? 100}</span>
                    <span className="flex items-center gap-1">
                      健康：
                      {failures > 0 ? <span className="text-amber-600">失败 {failures} 次</span> : <span className="text-emerald-600">良好</span>}
                      {account.health?.lastSuccessAt && <span className="truncate">（最近成功 {account.health.lastSuccessAt.slice(0, 16).replace('T', ' ')}）</span>}
                    </span>
                  </div>
                  {test && (
                    <div className={`flex items-center gap-1.5 text-xs ${test.ok ? 'text-emerald-600' : 'text-destructive'}`}>
                      {test.ok ? <CheckCircle2 className="size-3.5" /> : <AlertTriangle className="size-3.5" />}
                      {test.message}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </TabsContent>

        <TabsContent value="models" className="mt-4 space-y-3">
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">全局模型目录（{models.length} 个）</p>
            {canCatalog && (
              <Button size="sm" className="gap-2" onClick={openModelCreate}>
                <Plus className="size-4" />
                新增模型
              </Button>
            )}
          </div>
          <div className="space-y-2">
            {models.map((model) => (
              <div key={model.id} className="flex flex-wrap items-center justify-between gap-2 rounded-xl border p-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{model.name}</span>
                    <Badge variant={model.enabled ? 'default' : 'secondary'}>{model.enabled ? '启用' : '禁用'}</Badge>
                    <span className="text-xs text-muted-foreground">{model.type === 'image' ? '图片' : '文本'} · {model.protocol}</span>
                  </div>
                  <div className="truncate text-xs text-muted-foreground">modelId={model.modelId} · {model.baseUrl}</div>
                </div>
                {canCatalog && (
                  <div className="flex items-center gap-1">
                    <Button variant="outline" size="sm" onClick={() => openModelEdit(model)}>编辑</Button>
                    <Button variant="outline" size="sm" className="gap-1.5 text-destructive hover:text-destructive" onClick={() => void handleDeleteModel(model)}>
                      <Trash2 className="size-3.5" />删除
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </div>
        </TabsContent>

        <TabsContent value="pricing" className="mt-4 space-y-3">
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">价格配置：费用 = 单次价 + tokens × 每 token 单价（快照落库，改价不影响历史，A8）</p>
            {canPricing && (
              <Button size="sm" className="gap-2" onClick={openPriceCreate}>
                <Plus className="size-4" />
                配置价格
              </Button>
            )}
          </div>
          <div className="space-y-2">
            {pricing.map((row) => (
              <div key={row.id} className="flex flex-wrap items-center justify-between gap-2 rounded-xl border p-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{row.modelName}</span>
                    <span className="text-xs text-muted-foreground">{row.currency}</span>
                  </div>
                  <div className="text-xs text-muted-foreground">单次 {row.perRequestPrice} + {row.pricePerToken}/token</div>
                </div>
                {canPricing && (
                  <div className="flex items-center gap-1">
                    <Button variant="outline" size="sm" onClick={() => openPriceEdit(row)}>编辑</Button>
                    <Button variant="outline" size="sm" className="gap-1.5 text-destructive hover:text-destructive" onClick={() => void handleDeletePrice(row)}>
                      <Trash2 className="size-3.5" />删除
                    </Button>
                  </div>
                )}
              </div>
            ))}
            {pricing.length === 0 && (
              <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">暂无价格配置（无价格时费用按 0 计，H7）。</div>
            )}
          </div>
        </TabsContent>
      </Tabs>

      {/* 账号表单 */}
      <Dialog open={formOpen} onOpenChange={(open) => { if (!busy) setFormOpen(open); }}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editing ? '编辑账号' : '新增账号'}</DialogTitle>
            <DialogDescription>API Key 服务端 AES-GCM 加密存储；编辑时留空表示沿用已存密钥。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">名称 *</label>
              <Input value={form.name || ''} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} placeholder="主账号" />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">协议 *</label>
              <Select value={form.protocol || 'openai'} onValueChange={(v) => setForm((f) => ({ ...f, protocol: v }))} options={PROTOCOL_OPTIONS} />
            </div>
            <div className="space-y-1.5 sm:col-span-2">
              <label className="text-xs text-muted-foreground">Base URL *</label>
              <Input value={form.baseUrl || ''} onChange={(e) => setForm((f) => ({ ...f, baseUrl: e.target.value }))} placeholder="https://api.openai.com/v1" />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">API Key</label>
              <Input type="password" value={form.apiKey || ''} onChange={(e) => setForm((f) => ({ ...f, apiKey: e.target.value }))} placeholder={editing ? '留空沿用已存密钥' : 'sk-...'} />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">优先级</label>
              <Input type="number" value={form.priority || '100'} onChange={(e) => setForm((f) => ({ ...f, priority: e.target.value }))} />
            </div>
            <div className="space-y-1.5 sm:col-span-2">
              <label className="text-xs text-muted-foreground">模型作用域（逗号分隔的目录模型 UUID；留空 = 全部）</label>
              <Input value={form.modelScope || ''} onChange={(e) => setForm((f) => ({ ...f, modelScope: e.target.value }))} placeholder="全部模型" />
            </div>
            <div className="space-y-1.5 sm:col-span-2">
              <label className="text-xs text-muted-foreground">备注</label>
              <Input value={form.remark || ''} onChange={(e) => setForm((f) => ({ ...f, remark: e.target.value }))} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setFormOpen(false)} disabled={busy}>取消</Button>
            <Button className="gap-2" onClick={() => void handleSaveAccount()} disabled={busy || !form.name || !form.baseUrl}>
              <Save className="size-4" />保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 目录模型表单 */}
      <Dialog open={modelFormOpen} onOpenChange={(open) => { if (!busy) setModelFormOpen(open); }}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editingModel ? '编辑目录模型' : '新增目录模型'}</DialogTitle>
            <DialogDescription>模型目录全局共享；普通用户下拉将只显示启用且有可用账号的模型。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">类型 *</label>
              <Select value={modelForm.type || 'image'} onValueChange={(v) => setModelForm((f) => ({ ...f, type: v }))} options={[{ value: 'image', label: '图片' }, { value: 'text', label: '文本' }]} />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">协议 *</label>
              <Select value={modelForm.protocol || ''} onValueChange={(v) => setModelForm((f) => ({ ...f, protocol: v }))} options={PROTOCOL_OPTIONS} />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">显示名称 *</label>
              <Input value={modelForm.name || ''} onChange={(e) => setModelForm((f) => ({ ...f, name: e.target.value }))} />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">模型 ID *</label>
              <Input value={modelForm.modelId || ''} onChange={(e) => setModelForm((f) => ({ ...f, modelId: e.target.value }))} placeholder="gpt-image-2" />
            </div>
            <div className="space-y-1.5 sm:col-span-2">
              <label className="text-xs text-muted-foreground">Base URL *</label>
              <Input value={modelForm.baseUrl || ''} onChange={(e) => setModelForm((f) => ({ ...f, baseUrl: e.target.value }))} placeholder="https://api.openai.com" />
            </div>
            <div className="flex items-center justify-between rounded-lg border px-3 py-2 sm:col-span-2">
              <div>
                <p className="text-sm font-medium">启用</p>
                <p className="text-xs text-muted-foreground">禁用后普通用户不可见/不可选（A5）</p>
              </div>
              <Switch checked={modelEnabled} onCheckedChange={setModelEnabled} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setModelFormOpen(false)} disabled={busy}>取消</Button>
            <Button className="gap-2" onClick={() => void handleSaveModel()} disabled={busy || !modelForm.name || !modelForm.modelId || !modelForm.baseUrl}>
              <Save className="size-4" />保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 价格表单 */}
      <Dialog open={priceFormOpen} onOpenChange={(open) => { if (!busy) setPriceFormOpen(open); }}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>配置价格</DialogTitle>
            <DialogDescription>双口径计费：费用 = 单次价 + tokens × 每 token 单价（快照落库）。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">模型 *</label>
              <Select value={priceForm.modelId || ''} onValueChange={(v) => setPriceForm((f) => ({ ...f, modelId: v }))} options={modelOptions} />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">币种</label>
              <Input value={priceForm.currency || 'CNY'} onChange={(e) => setPriceForm((f) => ({ ...f, currency: e.target.value }))} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <label className="text-xs text-muted-foreground">单次价格</label>
                <Input type="number" step="0.000001" value={priceForm.perRequestPrice || '0'} onChange={(e) => setPriceForm((f) => ({ ...f, perRequestPrice: e.target.value }))} />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs text-muted-foreground">每 token 单价</label>
                <Input type="number" step="0.00000001" value={priceForm.pricePerToken || '0'} onChange={(e) => setPriceForm((f) => ({ ...f, pricePerToken: e.target.value }))} />
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPriceFormOpen(false)} disabled={busy}>取消</Button>
            <Button className="gap-2" onClick={() => void handleSavePrice()} disabled={busy || !priceForm.modelId}>
              <Save className="size-4" />保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
