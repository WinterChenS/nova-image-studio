'use client';

import { useEffect, useState } from 'react';
import { LogIn, UserPlus, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { login, register } from '@/lib/auth';

interface LoginDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * 登录/注册对话框（M2 T2.5）。登录成功后由 auth.ts 广播
 * `nova-auth-changed`，WorkspaceShell 据此刷新数据层。
 */
export function LoginDialog({ open, onOpenChange }: LoginDialogProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      setMode('login');
      setUsername('');
      setPassword('');
      setError(null);
      setBusy(false);
    }
  }, [open]);

  const handleSubmit = async () => {
    if (!username.trim() || !password) {
      setError('请输入用户名和密码');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (mode === 'login') {
        await login(username.trim(), password);
      } else {
        await register(username.trim(), password);
        await login(username.trim(), password);
      }
      onOpenChange(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!busy) onOpenChange(next);
    }}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <div className="flex items-center gap-2">
            {mode === 'login'
              ? <LogIn className="w-5 h-5 text-muted-foreground" />
              : <UserPlus className="w-5 h-5 text-muted-foreground" />}
            <DialogTitle>{mode === 'login' ? '登录' : '注册'}</DialogTitle>
          </div>
          <DialogDescription>
            {mode === 'login'
              ? '登录后模型配置、设置与任务将按用户隔离保存到服务器。'
              : '创建账号以在服务器上保存你的模型配置与设置。'}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground">用户名</label>
            <Input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="2-32 位字母、数字、下划线、短横线或中文"
              autoComplete="username"
              onKeyDown={(e) => { if (e.key === 'Enter') void handleSubmit(); }}
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground">密码</label>
            <Input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder={mode === 'register' ? '至少 6 位' : '请输入密码'}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              onKeyDown={(e) => { if (e.key === 'Enter') void handleSubmit(); }}
            />
          </div>

          {error && (
            <div className="rounded-lg border border-destructive/20 bg-destructive/10 p-3 text-sm text-destructive">
              {error}
            </div>
          )}

          <Button className="w-full gap-2" onClick={() => void handleSubmit()} disabled={busy}>
            {busy ? '请稍候...' : mode === 'login' ? '登录' : '注册并登录'}
          </Button>
          <div className="flex items-center justify-between text-sm">
            <button
              type="button"
              className="text-muted-foreground hover:text-foreground"
              onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null); }}
            >
              {mode === 'login' ? '没有账号？注册' : '已有账号？登录'}
            </button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
