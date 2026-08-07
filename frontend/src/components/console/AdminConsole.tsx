'use client';

/**
 * WIN-22 (F-42/A16) — 管理控制台：侧边栏 IA（项目管理 / 素材管理 / 用户管理 / 设置）。
 * 「用户管理」仅 role=admin 可见（F-42/G-2）；其余对所有登录用户开放。
 */

import { useEffect, useState } from 'react';
import { ArrowLeft, FolderKanban, Images, Settings, Users } from 'lucide-react';
import { useAppShell } from '@/components/console/AppShell';
import { ProjectsPanel } from '@/components/console/ProjectsPanel';
import { AssetsPanel } from '@/components/console/AssetsPanel';
import { UsersPanel } from '@/components/console/UsersPanel';
import { SettingsPanel } from '@/components/console/SettingsPanel';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { getCachedUser, getMe, type AuthUser } from '@/lib/auth';

type ConsoleTab = 'projects' | 'assets' | 'users' | 'settings';

const TABS: Array<{ value: ConsoleTab; label: string; icon: React.ComponentType<{ className?: string }>; adminOnly?: boolean }> = [
  { value: 'projects', label: '项目管理', icon: FolderKanban },
  { value: 'assets', label: '素材管理', icon: Images },
  { value: 'users', label: '用户管理', icon: Users, adminOnly: true },
  { value: 'settings', label: '设置', icon: Settings },
];

export function AdminConsole() {
  const { exitConsole } = useAppShell();
  const [activeTab, setActiveTab] = useState<ConsoleTab>('projects');
  const [user, setUser] = useState<AuthUser | null>(getCachedUser() ?? null);

  useEffect(() => {
    void getMe().then(setUser);
  }, []);

  const isAdmin = user?.role === 'admin';
  const visibleTabs = TABS.filter(tab => !tab.adminOnly || isAdmin);

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
        {/* 侧边栏 */}
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
                  activeTab === tab.value && 'bg-muted text-foreground',
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
          {activeTab === 'projects' && <ProjectsPanel />}
          {activeTab === 'assets' && <AssetsPanel />}
          {activeTab === 'users' && isAdmin && <UsersPanel />}
          {activeTab === 'settings' && <SettingsPanel />}
        </div>
      </div>
    </div>
  );
}
