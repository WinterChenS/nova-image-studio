import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AuthGate } from '@/components/AuthGate'
import type { AuthUser } from '@/lib/auth'

/**
 * WIN-46 (BUG-5, main 存量): 登录页被 `#app-boot-loader` 全屏遮罩永久覆盖，
 * 鼠标无法点击登录按钮。
 *
 * 根因：loader 的唯一移除点 `dismissBootLoader` 在 `useWideMode` 挂载时执行，
 * 而 `useWideMode` 仅由登录后的 WorkspaceShell 挂载；WIN-30 AuthGate 门禁后
 * 未登录只渲染 LoginPage（不挂载 WorkspaceShell）→ 遮罩永不消失，
 * z-[99999] 全屏拦截所有 pointer events。
 *
 * 修复契约：AuthGate 水合完成且判定为未登录（将渲染 LoginPage）时，
 * 必须移除 `#app-boot-loader`；登录态下保持现状（仍由 useWideMode 在
 * 宽屏状态落定后移除，避免破坏宽屏闪屏保护）。
 */

// layout.tsx 的 SSR 输出会在 <body> 下渲染 #app-boot-loader；jsdom 测试里手工注入，
// 复现"登录页被遮罩覆盖"的初始 DOM 形态。
function injectBootLoader(): void {
  const el = document.createElement('div')
  el.id = 'app-boot-loader'
  el.className = 'fixed inset-0 z-[99999] flex items-center justify-center bg-background'
  document.body.appendChild(el)
}

const mockUser: AuthUser = {
  id: 'u-test',
  username: 'tester',
  role: 'USER',
  permissions: [],
}

const mockAuthHydration = vi.hoisted(() => ({
  useAuthHydration: vi.fn(),
}))

vi.mock('@/hooks/useAuthHydration', () => mockAuthHydration)

describe('AuthGate 登录页 #app-boot-loader 遮罩（WIN-46 BUG-5 回归）', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('水合完成且未登录（渲染 LoginPage）时移除 #app-boot-loader，登录按钮可被点击命中', () => {
    injectBootLoader()
    mockAuthHydration.useAuthHydration.mockReturnValue({
      user: null,
      hydrated: true,
      handleLogout: vi.fn(),
    })

    render(
      <AuthGate>
        <div data-testid="workspace" />
      </AuthGate>
    )

    // 登录页已渲染
    expect(screen.getByRole('button', { name: /登录/ })).toBeTruthy()
    // 遮罩必须被移除 —— 否则其 z-[99999] 全屏拦截登录按钮的鼠标点击
    expect(document.getElementById('app-boot-loader')).toBeNull()
    // 页面上不得残留任何 z-[99999] 全屏 fixed 覆盖层（拦截 pointer events 的等价条件；
    // 真实命中测试由浏览器 E2E frontend/e2e/boot-loader.e2e.mjs 覆盖）
    const fixedOverlays = [...document.querySelectorAll('*')].filter((el) =>
      typeof el.className === 'string' &&
      /fixed/.test(el.className) &&
      /z-\[99999\]/.test(el.className)
    )
    expect(fixedOverlays).toHaveLength(0)
  })

  it('水合完成且已登录（渲染工作台）时不移除遮罩 —— 仍由 useWideMode 在宽屏状态落定后移除（登录态行为不变）', () => {
    injectBootLoader()
    mockAuthHydration.useAuthHydration.mockReturnValue({
      user: mockUser,
      hydrated: true,
      handleLogout: vi.fn(),
    })

    render(
      <AuthGate>
        <div data-testid="workspace" />
      </AuthGate>
    )

    expect(screen.getByTestId('workspace')).toBeTruthy()
    // 登录态下遮罩由 useWideMode 负责移除，AuthGate 不应提前移除（避免宽屏闪屏回归）
    expect(document.getElementById('app-boot-loader')).not.toBeNull()
  })
})
