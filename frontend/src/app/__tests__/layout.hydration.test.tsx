import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderToString } from 'react-dom/server'
import { act } from 'react'
import { hydrateRoot } from 'react-dom/client'
import RootLayout from '../layout'

/**
 * WIN-19: 浏览器扩展会在客户端向 <body> 注入属性（如 data-atm-ext-installed="1.30.02"），
 * 若 <body> 未设置 suppressHydrationWarning，React 水合时会因为服务端 HTML 与客户端
 * 属性不匹配而报 "A tree hydrated but some attributes of the server rendered HTML didn't
 * match the client properties..."（hydration mismatch）。
 *
 * 本测试用真实 SSR 输出 + jsdom 水合复现该场景，验证 <body> 上的属性注入不再触发
 * hydration mismatch 控制台错误。
 */

function renderLayoutToHtml(): string {
  return renderToString(
    <RootLayout>
      <p data-testid="page-content">hello</p>
    </RootLayout>
  )
}

/** 用 SSR 输出重建当前 jsdom 文档（jsdom 下 documentElement.outerHTML 赋值会被拒绝，故用 document.write） */
function loadHtmlIntoDocument(html: string): void {
  document.open()
  document.write(html)
  document.close()
}

function hydrationMismatchMessages(allErrors: string[]): string[] {
  return allErrors.filter((m) => /hydration|didn't match the client/i.test(m))
}

describe('RootLayout hydration safety (WIN-19)', () => {
  let consoleErrors: string[]

  beforeEach(() => {
    consoleErrors = []
    vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
      consoleErrors.push(args.map(String).join(' '))
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('SSR 输出包含完整的 <html>/<body> 结构（测试前置）', () => {
    const html = renderLayoutToHtml()
    expect(html).toContain('<html')
    expect(html).toContain('<body')
    expect(html).toContain('app-boot-loader')
  })

  it('无扩展属性注入时水合不报 hydration mismatch（基线）', async () => {
    const html = renderLayoutToHtml()
    loadHtmlIntoDocument(html)

    let root: ReturnType<typeof hydrateRoot> | undefined
    await act(async () => {
      root = hydrateRoot(
        document,
        <RootLayout>
          <p data-testid="page-content">hello</p>
        </RootLayout>
      )
    })

    expect(hydrationMismatchMessages(consoleErrors)).toEqual([])
    expect(document.querySelector('[data-testid="page-content"]')?.textContent).toBe('hello')
    root?.unmount()
  })

  it('浏览器扩展向 <body> 注入 data-atm-ext-installed 属性时不报 hydration mismatch（WIN-19 回归）', async () => {
    const html = renderLayoutToHtml()
    loadHtmlIntoDocument(html)
    // 模拟浏览器扩展（如 AT 记录扩展 1.30.02）在客户端向 <body> 注入属性
    document.body.setAttribute('data-atm-ext-installed', '1.30.02')

    let root: ReturnType<typeof hydrateRoot> | undefined
    await act(async () => {
      root = hydrateRoot(
        document,
        <RootLayout>
          <p data-testid="page-content">hello</p>
        </RootLayout>
      )
    })

    expect(hydrationMismatchMessages(consoleErrors)).toEqual([])
    // 水合不应破坏扩展注入的属性（只做无警告水合）
    expect(document.body.getAttribute('data-atm-ext-installed')).toBe('1.30.02')
    root?.unmount()
  })
})
