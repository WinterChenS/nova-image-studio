'use client';

/**
 * WIN-29 (T28, A16) — 角色与权限管理：角色列表 + 角色×权限矩阵（勾选）+ 权限码
 * 清单。保存权限变更经 PUT /api/nova/admin/roles/{id}/permissions，服务端写
 * audit_log 并失效权限缓存（≤1s 生效，A14）。权限 rbac.manage（admin 默认）。
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Loader2, RefreshCw, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import {
  fetchRbacPermissions, fetchRbacRoles, saveRolePermissions,
  type RbacPermission, type RbacRole,
} from '@/lib/admin-api';
import { useToast } from '@/components/console/useToast';

export function RbacPanel() {
  const { show } = useToast();
  const [roles, setRoles] = useState<RbacRole[]>([]);
  const [permissions, setPermissions] = useState<RbacPermission[]>([]);
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [roleList, permList] = await Promise.all([fetchRbacRoles(), fetchRbacPermissions()]);
      setRoles(roleList);
      setPermissions(permList);
      const first = selectedRoleId && roleList.some(r => r.id === selectedRoleId) ? selectedRoleId : roleList[0]?.id ?? null;
      setSelectedRoleId(first);
      const firstRole = roleList.find(r => r.id === first);
      setChecked(new Set(firstRole?.permissions ?? []));
      setDirty(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载角色权限失败');
    } finally {
      setLoading(false);
    }
  }, [selectedRoleId]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async load (repo pattern)
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const selectRole = useCallback((roleId: string) => {
    setSelectedRoleId(roleId);
    const role = roles.find(r => r.id === roleId);
    setChecked(new Set(role?.permissions ?? []));
    setDirty(false);
  }, [roles]);

  const toggle = useCallback((code: string) => {
    setChecked(prev => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code); else next.add(code);
      return next;
    });
    setDirty(true);
  }, []);

  const selectedRole = useMemo(() => roles.find(r => r.id === selectedRoleId) ?? null, [roles, selectedRoleId]);

  const menuPerms = useMemo(() => permissions.filter(p => p.type === 'menu'), [permissions]);
  const buttonPerms = useMemo(() => permissions.filter(p => p.type === 'button'), [permissions]);

  const handleSave = useCallback(async () => {
    if (!selectedRole) return;
    setSaving(true);
    setError(null);
    try {
      await saveRolePermissions(selectedRole.id, Array.from(checked).sort());
      setDirty(false);
      show(`已保存「${selectedRole.name}」的权限（变更已记录审计日志）`, "success");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setSaving(false);
    }
  }, [selectedRole, checked, load, show]);

  if (loading) {
    return <div className="flex items-center justify-center gap-2 p-10 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" /> 加载中…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold tracking-tight">角色与权限</h2>
          <p className="text-xs text-muted-foreground">勾选角色×权限矩阵后保存；变更即时生效（≤1s）并写入审计日志（A16）。</p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading} className="gap-1.5">
            <RefreshCw className="size-4" /> 刷新
          </Button>
          <Button size="sm" onClick={() => void handleSave()} disabled={saving || !dirty || !selectedRole} className="gap-1.5">
            <Save className="size-4" /> {saving ? '保存中…' : '保存'}
          </Button>
        </div>
      </div>

      {error && <div className="rounded-xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">{error}</div>}

      <div className="flex flex-col gap-4 sm:flex-row">
        {/* 角色列表 */}
        <nav className="flex shrink-0 flex-row gap-1 overflow-x-auto sm:w-44 sm:flex-col sm:overflow-visible">
          {roles.map(role => (
            <button
              key={role.id}
              type="button"
              onClick={() => selectRole(role.id)}
              className={cn(
                'rounded-xl px-3 py-2 text-left text-sm',
                selectedRoleId === role.id ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-muted/60',
              )}
            >
              <div className="font-medium">{role.name}</div>
              <div className="text-xs text-muted-foreground">{role.code}{role.builtin ? ' · 内置' : ''}</div>
            </button>
          ))}
        </nav>

        {/* 权限矩阵 */}
        <div className="min-w-0 flex-1 rounded-2xl border border-border/70 bg-card p-3">
          {selectedRole ? (
            <div className="space-y-4">
              <div className="text-sm text-muted-foreground">
                当前角色：<span className="font-medium text-foreground">{selectedRole.name}</span>
                {dirty && <span className="ml-2 text-warning">（有未保存变更）</span>}
              </div>

              {/* 菜单权限 */}
              <div>
                <div className="mb-1.5 text-xs font-medium text-muted-foreground">菜单权限</div>
                <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                  {menuPerms.map(perm => (
                    <label key={perm.code} className="flex cursor-pointer items-center gap-2 rounded-lg border border-border/60 px-2.5 py-1.5 text-sm hover:bg-muted/50">
                      <input
                        type="checkbox"
                        className="size-4 accent-primary"
                        checked={checked.has(perm.code)}
                        onChange={() => toggle(perm.code)}
                      />
                      <span className="min-w-0 flex-1 truncate">
                        {perm.label}
                        <span className="ml-1.5 font-mono text-[11px] text-muted-foreground">{perm.code}</span>
                      </span>
                    </label>
                  ))}
                </div>
              </div>

              {/* 按钮权限 */}
              {buttonPerms.length > 0 && (
                <div>
                  <div className="mb-1.5 text-xs font-medium text-muted-foreground">按钮权限</div>
                  <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                    {buttonPerms.map(perm => (
                      <label key={perm.code} className="flex cursor-pointer items-center gap-2 rounded-lg border border-border/60 px-2.5 py-1.5 text-sm hover:bg-muted/50">
                        <input
                          type="checkbox"
                          className="size-4 accent-primary"
                          checked={checked.has(perm.code)}
                          onChange={() => toggle(perm.code)}
                        />
                        <span className="min-w-0 flex-1 truncate">
                          {perm.label}
                          <span className="ml-1.5 font-mono text-[11px] text-muted-foreground">{perm.code}</span>
                        </span>
                      </label>
                    ))}
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="p-6 text-center text-sm text-muted-foreground">请选择角色</div>
          )}
        </div>
      </div>

      {/* 权限码清单 */}
      <details className="rounded-2xl border border-border/70 bg-card p-3">
        <summary className="cursor-pointer text-sm font-medium">权限码清单（{permissions.length} 个）</summary>
        <div className="mt-2 grid grid-cols-1 gap-1 text-xs sm:grid-cols-2 lg:grid-cols-3">
          {permissions.map(p => (
            <div key={p.code} className="flex items-center justify-between gap-2 rounded-lg px-2 py-1">
              <span className="truncate">
                <span className="font-mono">{p.code}</span>
                <span className="ml-1.5 text-muted-foreground">{p.label}</span>
              </span>
              <span className="shrink-0 text-muted-foreground">{p.type === 'menu' ? '菜单' : '按钮'}</span>
            </div>
          ))}
        </div>
      </details>
    </div>
  );
}
