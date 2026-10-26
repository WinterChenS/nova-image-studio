import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

/**
 * WIN-44 BUG-4 — 迁移完成提示「历史数据已同步到云端」永不渲染：
 * runAll 先 setPending([]) 再 setMessage(...)，横幅因 pending.length===0 提前
 * return null。修复：成功态由独立 done 字段承载，提示在关闭前可见。
 *
 * WIN-42 (T17, C10) — 已有迁移标记时启动即清理本地存量（runLegacyCleanup）。
 */

const runAgentMigrationMock = vi.hoisted(() => vi.fn());
const runLegacyCleanupMock = vi.hoisted(() => vi.fn().mockResolvedValue({}));

vi.mock('@/lib/auth', () => ({
  isLoggedIn: () => true,
  authFetch: vi.fn(),
  readApiError: vi.fn(),
}));

vi.mock('@/lib/migration', () => ({
  hasLocalAgentData: vi.fn(async () => true),
  hasLocalCanvasData: vi.fn(async () => false),
  isFeatureMigrated: vi.fn(() => false),
  runAgentMigration: runAgentMigrationMock,
  runCanvasMigration: vi.fn(),
  runLegacyCleanup: runLegacyCleanupMock,
}));

import { MigrationBanner } from '@/components/MigrationBanner';

describe('WIN-44 BUG-4 — 迁移完成提示可见', () => {
  beforeEach(() => {
    runAgentMigrationMock.mockReset();
    runLegacyCleanupMock.mockClear();
  });

  it('迁移完成后「历史数据已同步到云端」渲染（不因 pending 清空提前消失）', async () => {
    runAgentMigrationMock.mockResolvedValue(1);
    render(<MigrationBanner features={['agent']} />);

    expect(await screen.findByRole('button', { name: /开始迁移/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /开始迁移/ }));

    expect(await screen.findByText('历史数据已同步到云端')).toBeInTheDocument();
  });
});
