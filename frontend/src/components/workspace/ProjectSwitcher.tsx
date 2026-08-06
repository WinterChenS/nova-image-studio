'use client';

/**
 * WIN-22 (F-3/Q11) — 工作台顶栏轻量「当前项目」切换器（仅切换，供生成与素材
 * 入库定位；项目管理全部操作在管理控制台）。选中项持久化到 localStorage +
 * 服务端 workbench.defaultProjectId。
 */

import { useCurrentProject } from '@/hooks/useCurrentProject';
import { Select } from '@/components/ui/select';

export function ProjectSwitcher() {
  const { projects, loading, currentProject, setCurrentProject } = useCurrentProject();

  if (loading && projects.length === 0) {
    return (
      <span className="inline-flex h-7 items-center rounded-lg border border-input px-2.5 text-xs text-muted-foreground">
        加载项目…
      </span>
    );
  }
  if (projects.length === 0) {
    return null;
  }

  return (
    <Select
      value={currentProject?.id ?? ''}
      onValueChange={value => void setCurrentProject(value)}
      options={projects.map(p => ({ value: p.id, label: p.name }))}
      placeholder="当前项目"
      className="w-36"
      size="sm"
      ariaLabel="当前项目"
    />
  );
}
