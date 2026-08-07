'use client';

/**
 * WIN-22 (F-42/A16) + WIN-25 (T23, A13) — 管理控制台：侧边栏 IA 权限化。
 * TABS 由 isAdmin 粗粒度过滤改为**权限码过滤**（hasPerm）：
 * 项目管理 project.manage / 素材管理 asset.manage / 用户管理 user.manage /
 * 账号池管理 account.manage / 审计与费用 audit.view；「设置」对所有登录用户开放。
 * 普通用户无管理入口（TABS 不渲染）+ 直调接口 403 提示（A13）。
 */

import { useEffect, useState } from 'react';
import {
  ArrowLeft, CreditCard, FolderKanban, Images, Settings, Shield, Users, Wallet,
} from 'lucide-react';
import { useAppShell } from '@/components/console/AppShell';
import { ProjectsPanel } from '@/components/console/ProjectsPanel';
import { AssetsPanel } from '@/components/console/AssetsPanel';
import { UsersPanel } from '@/components/console/UsersPanel';
import { SettingsPanel } from '@/components/console/SettingsPanel';
import { AccountPoolPanel } from '@/components/console/AccountPoolPanel';
import { AuditPanel } from '@/components/console/AuditPanel';
import { RbacPanel } from '@/components/console/RbacPanel';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { getCachedUser, getMe, type AuthUser } from '@/lib/auth';
import { hasPerm } from '@/lib/permissions';

type ConsoleTab = 'projects' | 'assets' | 'users' | 'accounts' | 'audit' | 'rbac' | 'settings';

const TABS: Array<{
  value: ConsoleTab;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  perm: string;
}> = [
  { value: 'projects', label: '项目管理', icon: FolderKanban, perm: 'project.manage' },
  { value: 'assets', label: '素材管理', icon: Images, perm: 'asset.manage' },
  { value: 'users', label: '用户管理', icon: Users, perm: 'user.manage' },
  { value: 'accounts', label: '账号池管理', icon: Wallet, perm: 'account.manage' },
  { value: 'audit', label: '审计与费用', icon: CreditCard, perm: 'audit.view' },
  { value: 'rbac', label: '角色与权限', icon: Shield, perm: 'rbac.manage' },
  { value: 'settings', label: '设置', icon: Settings, perm: '' }, // 所有登录用户可见
];

export function AdminConsole() {
  const { exitConsole } = useAppShell();
  const [activeTab, setActiveTab] = useState<ConsoleTab>('projects');
  const [user, setUser] = useState<AuthUser | null>(getCachedUser() ?? null);

  useEffect(() => {
    void getMe().then(setUser);
  }, []);

  const visibleTabs = TABS.filter((tab) => tab.perm === '' || hasPerm(tab.perm, user));
  // 当前激活 Tab 无权限时回退到第一个可见 Tab
  const effectiveTab = visibleTabs.some((tab) => tab.value === activeTab) ? activeTab : visibleTabs[0]?.value || 'settings';

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-6xl flex-col gap-4 px-3 py-3 sm:px-6 sm:py-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={exitConsole} className="gap-1.5">
            <ArrowLeft className="size-4" />
            返回工作台
          </Button>
          <h1 className="text-lg font-semibold tracking-tight">管理控制台</h1>
        </div>
        {user && <span className="text-xs text-muted-foreground">当前用户：{user.username}</span>}
      </div>

      <div className="flex flex-1 flex-col gap-4 sm:flex-row">
        {/* 侧边栏（权限码过滤，A13） */}
        <nav className="flex shrink-0 flex-row gap-1 overflow-x-auto sm:w-48 sm:flex-col sm:overflow-visible">
          {visibleTabs.map(tab => {
            const Icon = tab.icon;
            return (
              <Button
                key={tab.value}
                variant="ghost"
                size="sm"
                onClick={() => setActiveTab(tab.value)}
                className={cn(
                  'justify-start gap-2 rounded-xl px-3 text-sm',
                  effectiveTab === tab.value && 'bg-muted text-foreground',
                )}
              >
                <Icon className="size-4 shrink-0" />
                {tab.label}
              </Button>
            );
          })}
        </nav>

        {/* 内容区 */}
        <div className="min-w-0 flex-1">
          {effectiveTab === 'projects' && <ProjectsPanel />}
          {effectiveTab === 'assets' && <AssetsPanel />}
          {effectiveTab === 'users' && <UsersPanel />}
          {effectiveTab === 'accounts' && <AccountPoolPanel />}
          {effectiveTab === 'audit' && <AuditPanel />}
          {effectiveTab === 'rbac' && <RbacPanel />}
          {effectiveTab === 'settings' && <SettingsPanel />}
        </div>
      </div>
    </div>
  );
}
