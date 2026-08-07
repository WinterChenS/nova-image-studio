'use client';

/**
 * WIN-29 (T28, A16) — 角色与权限管理界面：角色列表 + 角色×权限矩阵勾选 +
 * 权限码清单。保存矩阵写 audit_log（A16）并即时生效（A14 缓存失效）。
 * 内置角色（builtin）保存前确认（A15：内置角色不可删除，但矩阵可编辑）。
 * 权限 rbac.manage（AdminConsole Tab 权限门控 + 后端 @PreAuthorize 403 兜底）。
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Loader2, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/console/useToast';
import { cn } from '@/lib/utils';
import {
  fetchPermissions, fetchRolePermissions, fetchRoles, saveRolePermissions,
  type RbacPermission, type RbacRole,
} from '@/lib/admin-api';

export function RbacPanel() {
  const { show } = useToast();
  const [roles, setRoles] = useState<RbacRole[]>([]);
  const [permissions, setPermissions] = useState<RbacPermission[]>([]);
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadBase = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [roleList, permList] = await Promise.all([fetchRoles(), fetchPermissions()]);
      setRoles(roleList);
      setPermissions(permList);
      if (roleList.length > 0 && !selectedRoleId) {
        setSelectedRoleId(roleList[0].id);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载角色与权限失败');
    } finally {
      setLoading(false);
    }
  }, [selectedRoleId]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async load (repo pattern)
    void loadBase();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 选中角色时加载其已授权限
  useEffect(() => {
    if (!selectedRoleId) return;
    let cancelled = false;
    fetchRolePermissions(selectedRoleId)
      .then(res => { if (!cancelled) setChecked(new Set(res.permissionIds)); })
      .catch(err => { if (!cancelled) setError(err instanceof Error ? err.message : '加载角色权限失败'); });
    return () => { cancelled = true; };
  }, [selectedRoleId]);

  const selectedRole = useMemo(
    () => roles.find(r => r.id === selectedRoleId) ?? null,
    [roles, selectedRoleId],
  );

  // 权限按 parent_code 分组：一级菜单 → 子权限（缩进）
  const grouped = useMemo(() => {
    const children = new Map<string, RbacPermission[]>();
    const roots: RbacPermission[] = [];
    for (const p of permissions) {
      if (p.parentCode) {
        const list = children.get(p.parentCode) ?? [];
        list.push(p);
        children.set(p.parentCode, list);
      } else {
        roots.push(p);
      }
    }
    return { roots, children };
  }, [permissions]);

  const toggle = (permissionId: string) => {
    setChecked(prev => {
      const next = new Set(prev);
      if (next.has(permissionId)) next.delete(permissionId);
      else next.add(permissionId);
      return next;
    });
  };

  const handleSave = async () => {
    if (!selectedRole) return;
    const proceed = selectedRole.builtin
      ? window.confirm(
          `「${selectedRole.name}」为内置角色，修改权限矩阵会影响该角色的所有用户（变更将写入审计日志）。确定保存？`,
        )
      : true;
    if (!proceed) return;
    setSaving(true);
    setError(null);
    try {
      const result = await saveRolePermissions(selectedRole.id, [...checked]);
      show(`权限矩阵已保存（${result.before.length} → ${result.after.length} 项），已写入审计日志`, 'success');
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败');
      show(err instanceof Error ? err.message : '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold">角色与权限</h2>
          <p className="text-xs text-muted-foreground">
            角色×权限矩阵保存时写入变更审计日志（A16），权限变更 ≤1s 生效（A14）。
          </p>
        </div>
        {selectedRole && (
          <Button variant="default" size="sm" className="gap-2" onClick={() => void handleSave()} disabled={saving}>
            {saving ? <Loader2 className="size-4 animate-spin" /> : <Save className="size-4" />}
            {saving ? '保存中...' : '保存矩阵'}
          </Button>
        )}
      </div>

      {error && (
        <div className="rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {error}
        </div>
      )}

      <div className="flex flex-col gap-4 lg:flex-row">
        {/* 角色列表 */}
        <div className="w-full shrink-0 lg:w-52">
          <h3 className="mb-2 text-xs font-medium text-muted-foreground">角色</h3>
          <div className="flex flex-row gap-1 overflow-x-auto lg:flex-col">
            {roles.map(role => (
              <Button
                key={role.id}
                variant="ghost"
                size="sm"
                onClick={() => setSelectedRoleId(role.id)}
                className={cn(
                  'justify-start gap-2 rounded-xl px-3 text-sm',
                  selectedRoleId === role.id && 'bg-muted text-foreground',
                )}
              >
                <span>{role.name}</span>
                {role.builtin && (
                  <span className="rounded-full bg-muted-foreground/10 px-1.5 py-0.5 text-[10px] text-muted-foreground">内置</span>
                )}
              </Button>
            ))}
          </div>
        </div>

        {/* 矩阵 */}
        <div className="min-w-0 flex-1 rounded-xl border bg-card p-4">
          {selectedRole ? (
            <>
              <h3 className="mb-3 text-sm font-semibold">
                权限矩阵：{selectedRole.name}（{selectedRole.code}）
              </h3>
              <div className="space-y-3">
                {grouped.roots.map(root => {
                  const sub = grouped.children.get(root.code) ?? [];
                  return (
                    <div key={root.id} className="rounded-lg border bg-muted/30 p-3">
                      <label className="flex cursor-pointer items-center gap-2 text-sm font-medium">
                        <input
                          type="checkbox"
                          checked={checked.has(root.id)}
                          onChange={() => toggle(root.id)}
                          className="size-4 accent-primary"
                        />
                        {root.label}
                        <span className="text-[10px] text-muted-foreground">{root.code}</span>
                        {root.apiPath && <code className="text-[10px] text-muted-foreground">{root.apiPath}</code>}
                      </label>
                      {sub.length > 0 && (
                        <div className="mt-2 space-y-1.5 pl-6">
                          {sub.map(p => (
                            <label key={p.id} className="flex cursor-pointer items-center gap-2 text-xs">
                              <input
                                type="checkbox"
                                checked={checked.has(p.id)}
                                onChange={() => toggle(p.id)}
                                className="size-4 accent-primary"
                              />
                              {p.label}
                              <span className="text-[10px] text-muted-foreground">{p.code}</span>
                              {p.apiPath && <code className="text-[10px] text-muted-foreground">{p.apiPath}</code>}
                            </label>
                          ))}
                        </div>
                      )}
                    </div>
                  );
                })}
                {permissions.length === 0 && (
                  <div className="py-8 text-center text-sm text-muted-foreground">暂无权限码</div>
                )}
              </div>
            </>
          ) : (
            <div className="py-8 text-center text-sm text-muted-foreground">请选择角色</div>
          )}
        </div>
      </div>

      {/* 权限码清单 */}
      <div className="rounded-xl border bg-card">
        <div className="border-b px-4 py-3">
          <h3 className="text-sm font-semibold">权限码清单</h3>
          <p className="text-xs text-muted-foreground">共 {permissions.length} 个权限码（V6 种子，api_path 为后端鉴权映射）。</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-muted-foreground">
              <tr className="border-b">
                <th className="px-4 py-2 font-medium">权限码</th>
                <th className="px-4 py-2 font-medium">名称</th>
                <th className="px-4 py-2 font-medium">类型</th>
                <th className="px-4 py-2 font-medium">父级</th>
                <th className="px-4 py-2 font-medium">关联接口</th>
              </tr>
            </thead>
            <tbody>
              {permissions.map(p => (
                <tr key={p.id} className="border-b last:border-0 hover:bg-muted/40">
                  <td className="px-4 py-2 font-mono text-[11px]">{p.code}</td>
                  <td className="px-4 py-2">{p.label}</td>
                  <td className="px-4 py-2">{p.type === 'menu' ? '菜单' : '按钮'}</td>
                  <td className="px-4 py-2">{p.parentCode ?? '—'}</td>
                  <td className="px-4 py-2 font-mono text-[11px] text-muted-foreground">{p.apiPath ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
