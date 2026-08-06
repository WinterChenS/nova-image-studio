'use client';

/**
 * WIN-22 (T16 / F-40..F-42, P1) — 用户管理页（仅管理员）：列表（用户名/角色/
 * 状态/注册时间/最近登录）、启用/禁用、设为/取消管理员、重置密码（管理员代设，Q4）。
 * 服务端守卫：不可禁用/降级自身与最后一名 admin。
 */

import { useCallback, useEffect, useState } from 'react';
import { KeyRound, RefreshCw, Shield, ShieldOff, UserX, UserCheck } from 'lucide-react';
import { authFetch, readApiError } from '@/lib/auth';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { ConfirmDialog } from '@/components/workspace/dialogs/ConfirmDialog';
import { useToast } from '@/components/console/useToast';

interface AdminUser {
  id: string;
  username: string;
  role: 'user' | 'admin';
  status: 'active' | 'disabled';
  createdAt?: string | null;
  lastLoginAt?: string | null;
}

export function UsersPanel() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [resetTarget, setResetTarget] = useState<AdminUser | null>(null);
  const [newPassword, setNewPassword] = useState('');
  const [confirmTarget, setConfirmTarget] = useState<{ user: AdminUser; action: () => Promise<void>; message: string } | null>(null);
  const toast = useToast();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response = await authFetch('/api/nova/admin/users', { cache: 'no-store' });
      if (!response.ok) throw await readApiError(response);
      setUsers((await response.json()) as AdminUser[]);
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async user list load
    void load();
  }, [load]);

  const patch = useCallback(async (id: string, body: Record<string, string>) => {
    const response = await authFetch(`/api/nova/admin/users/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!response.ok) throw await readApiError(response);
  }, []);

  const handleToggleStatus = async (user: AdminUser) => {
    const disabling = user.status === 'active';
    setConfirmTarget({
      user,
      message: disabling ? `禁用「${user.username}」后该账号将无法登录。确定？` : `恢复「${user.username}」的登录权限？`,
      action: async () => {
        await patch(user.id, { status: disabling ? 'disabled' : 'active' });
        toast.show(disabling ? '账号已禁用' : '账号已恢复', 'success');
      },
    });
  };

  const handleToggleRole = async (user: AdminUser) => {
    const demoting = user.role === 'admin';
    setConfirmTarget({
      user,
      message: demoting ? `将「${user.username}」降为普通成员？` : `将「${user.username}」设为管理员？`,
      action: async () => {
        await patch(user.id, { role: demoting ? 'user' : 'admin' });
        toast.show(demoting ? '已取消管理员' : '已设为管理员', 'success');
      },
    });
  };

  const handleResetPassword = async () => {
    if (!resetTarget) return;
    if (!newPassword || newPassword.length < 6) {
      toast.show('密码长度至少 6 位', 'error');
      return;
    }
    try {
      const response = await authFetch(`/api/nova/admin/users/${encodeURIComponent(resetTarget.id)}/reset-password`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: newPassword }),
      });
      if (!response.ok) throw await readApiError(response);
      toast.show('密码已重置', 'success');
      setResetTarget(null);
      setNewPassword('');
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
  };

  const runConfirm = async () => {
    if (!confirmTarget) return;
    try {
      await confirmTarget.action();
      await load();
    } catch (e) {
      toast.show(e instanceof Error ? e.message : String(e), 'error');
    }
    setConfirmTarget(null);
  };

  if (loading) {
    return <div className="py-12 text-center text-sm text-muted-foreground">加载中…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold">用户列表（{users.length}）</h2>
        <Button variant="ghost" size="sm" onClick={() => void load()} className="gap-1">
          <RefreshCw className="size-3.5" /> 刷新
        </Button>
      </div>

      <div className="overflow-x-auto rounded-xl border border-border/70">
        <table className="w-full text-left text-sm">
          <thead className="border-b bg-muted/40 text-xs text-muted-foreground">
            <tr>
              <th className="px-3 py-2">用户名</th>
              <th className="px-3 py-2">角色</th>
              <th className="px-3 py-2">状态</th>
              <th className="px-3 py-2">注册时间</th>
              <th className="px-3 py-2">最近登录</th>
              <th className="px-3 py-2 text-right">操作</th>
            </tr>
          </thead>
          <tbody>
            {users.map(user => (
              <tr key={user.id} className="border-b last:border-0">
                <td className="px-3 py-2 font-medium">{user.username}</td>
                <td className="px-3 py-2">
                  {user.role === 'admin' ? <Badge>管理员</Badge> : <Badge variant="secondary">成员</Badge>}
                </td>
                <td className="px-3 py-2">
                  <Badge variant={user.status === 'active' ? 'secondary' : 'destructive'}>
                    {user.status === 'active' ? '正常' : '已禁用'}
                  </Badge>
                </td>
                <td className="px-3 py-2 text-xs text-muted-foreground">
                  {user.createdAt ? new Date(user.createdAt).toLocaleString() : '—'}
                </td>
                <td className="px-3 py-2 text-xs text-muted-foreground">
                  {user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : '—'}
                </td>
                <td className="px-3 py-2">
                  <div className="flex justify-end gap-1">
                    <Button variant="ghost" size="sm" title={user.status === 'active' ? '禁用' : '启用'} onClick={() => void handleToggleStatus(user)}>
                      {user.status === 'active' ? <UserX className="size-3.5" /> : <UserCheck className="size-3.5" />}
                    </Button>
                    <Button variant="ghost" size="sm" title={user.role === 'admin' ? '取消管理员' : '设为管理员'} onClick={() => void handleToggleRole(user)}>
                      {user.role === 'admin' ? <ShieldOff className="size-3.5" /> : <Shield className="size-3.5" />}
                    </Button>
                    <Button variant="ghost" size="sm" title="重置密码" onClick={() => { setResetTarget(user); setNewPassword(''); }}>
                      <KeyRound className="size-3.5" />
                    </Button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {resetTarget && (
        <Dialog open onOpenChange={open => { if (!open) setResetTarget(null); }}>
          <DialogContent className="sm:max-w-sm">
            <DialogHeader>
              <DialogTitle>重置「{resetTarget.username}」的密码</DialogTitle>
            </DialogHeader>
            <Input
              type="password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              placeholder="新密码（至少 6 位）"
              autoFocus
            />
            <DialogFooter>
              <Button variant="outline" onClick={() => setResetTarget(null)}>取消</Button>
              <Button onClick={handleResetPassword}>重置</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}

      {confirmTarget && (
        <ConfirmDialog
          title="确认操作"
          message={confirmTarget.message}
          confirmText="确认"
          onConfirm={runConfirm}
          onCancel={() => setConfirmTarget(null)}
        />
      )}
    </div>
  );
}
