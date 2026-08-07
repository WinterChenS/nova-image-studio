'use client';

/**
 * WIN-25 (T22, ADR-33) — 全页登录页（登录 / 注册 / 基础忘记密码）。
 * 由 AuthGate 在未登录时渲染（SPA 静态导出形态下的等效路由守卫，A17/A18）；
 * 风格沿用 LoginDialog 的 UI 体系。登录/注册成功后广播 nova-auth-changed，
 * AuthGate 自动进入工作台；忘记密码提交申请 → 引导联系管理员重置（D3）。
 */

import { useState } from 'react';
import { KeyRound, LogIn, Sparkles, UserPlus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { forgotPassword, login, register } from '@/lib/auth';

type Mode = 'login' | 'register' | 'forgot';

export function LoginPage() {
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const switchMode = (next: Mode) => {
    setMode(next);
    setError(null);
    setNotice(null);
    setPassword('');
  };

  const handleSubmit = async () => {
    if (!username.trim()) {
      setError('请输入用户名');
      return;
    }
    if (mode !== 'forgot' && !password) {
      setError('请输入密码');
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (mode === 'login') {
        await login(username.trim(), password);
      } else if (mode === 'register') {
        await register(username.trim(), password);
        await login(username.trim(), password);
      } else {
        const message = await forgotPassword(username.trim());
        setNotice(message);
        setMode('login');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm space-y-6">
        <div className="space-y-2 text-center">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-primary/10">
            <Sparkles className="h-6 w-6 text-primary" />
          </div>
          <h1 className="text-xl font-semibold tracking-tight">Nova Image</h1>
          <p className="text-sm text-muted-foreground">
            {mode === 'login' && '登录后使用生图工作台与管理控制台'}
            {mode === 'register' && '创建账号以使用 Nova Image'}
            {mode === 'forgot' && '重置密码'}
          </p>
        </div>

        <div className="space-y-3 rounded-xl border bg-card p-6">
          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground">用户名</label>
            <Input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="2-32 位字母、数字、下划线、短横线或中文"
              autoComplete="username"
              autoFocus
              onKeyDown={(e) => { if (e.key === 'Enter') void handleSubmit(); }}
            />
          </div>

          {mode !== 'forgot' && (
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
          )}

          {error && (
            <div className="rounded-lg border border-destructive/20 bg-destructive/10 p-3 text-sm text-destructive">
              {error}
            </div>
          )}
          {notice && (
            <div className="rounded-lg border border-emerald-500/20 bg-emerald-500/10 p-3 text-sm text-emerald-700 dark:text-emerald-400">
              {notice}
            </div>
          )}

          <Button className="w-full gap-2" onClick={() => void handleSubmit()} disabled={busy}>
            {mode === 'login' && <LogIn className="h-4 w-4" />}
            {mode === 'register' && <UserPlus className="h-4 w-4" />}
            {mode === 'forgot' && <KeyRound className="h-4 w-4" />}
            {busy
              ? '请稍候...'
              : mode === 'login' ? '登录'
                : mode === 'register' ? '注册并登录' : '提交申请'}
          </Button>

          <div className="flex items-center justify-between text-sm">
            {mode === 'login' ? (
              <>
                <button type="button" className="text-muted-foreground hover:text-foreground" onClick={() => switchMode('forgot')}>
                  忘记密码？
                </button>
                <button type="button" className="text-primary hover:underline" onClick={() => switchMode('register')}>
                  注册账号
                </button>
              </>
            ) : (
              <button type="button" className="text-muted-foreground hover:text-foreground" onClick={() => switchMode('login')}>
                已有账号？登录
              </button>
            )}
          </div>
        </div>

        <p className="text-center text-xs text-muted-foreground">
          忘记密码时提交申请，管理员会为你重置（D3）
        </p>
      </div>
    </div>
  );
}
