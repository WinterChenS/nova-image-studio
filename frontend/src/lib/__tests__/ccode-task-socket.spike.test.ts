// @vitest-environment node
/**
 * M0 spike (WIN-10 / T0.4): real frontend `ccode-task-socket` client against the
 * Spring Boot WS server.
 *
 * Prerequisites:
 *  1. Start the Spring backend (backend-spring) with its `.env`, then:
 *  2. export SPIKE_WS_URL=ws://localhost:8080/api/nova/ws
 *  3. npx vitest run src/lib/__tests__/ccode-task-socket.spike.test.ts
 *
 * Verifies with the REAL client module (no shims inside the module):
 *  - connect + subscribeTasks immediate push
 *  - REST upsert → WS broadcast received by the frontend client
 *  - subscribeQueue → immediate queueStatus
 *  - heartbeat: client pings every 25s, connection survives >1 ping cycle
 *  - disconnect → reconnect with backoff → re-subscribe on open
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import type { NovaTaskResponse } from '@/lib/ccode-task-client'
import type { QueueUpdateHandler, TaskUpdateHandler } from '@/lib/ccode-task-socket'

const WS_URL = process.env.SPIKE_WS_URL || ''
const API_BASE = process.env.SPIKE_API_BASE || 'http://localhost:8080'

/** Minimal structural type for the socket instance (class is not exported). */
interface SocketLike {
  ws: { readyState: number; close: (code: number, reason: string) => void } | null
  subscribeTask(taskId: string, handler: TaskUpdateHandler): () => void
  subscribeQueue(handler: QueueUpdateHandler): () => void
  disable(): void
}

describe.skipIf(!WS_URL)('ccode-task-socket vs Spring WS server (M0 spike)', () => {
  let socket: SocketLike

  // Shim browser globals the client touches, before importing the module.
  beforeAll(async () => {
    ;(globalThis as { window?: unknown }).window = {
      location: { href: API_BASE + '/' },
      addEventListener: () => {},
    }
    ;(globalThis as { document?: unknown }).document = {
      visibilityState: 'visible',
      addEventListener: () => {},
    }
    const mod = await import('@/lib/ccode-task-socket')
    socket = mod.novaTaskSocket as SocketLike
  })

  afterAll(() => {
    socket?.disable()
  })

  async function createTask(id: string, status: string): Promise<void> {
    const resp = await fetch(`${API_BASE}/api/nova/spike/tasks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, status }),
    })
    expect(resp.ok).toBe(true)
  }

  async function waitFor(pred: () => boolean, timeoutMs = 8000): Promise<void> {
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
      if (pred()) return
      await new Promise((r) => setTimeout(r, 50))
    }
    throw new Error('waitFor timeout')
  }

  it('subscribe → immediate push, REST broadcast received, queueStatus, heartbeat alive', async () => {
    const taskId = 'fe-spike-' + Date.now()
    await createTask(taskId, 'queued')

    const updates: string[] = []
    const queueStats: unknown[] = []
    socket.subscribeTask(taskId, (task: NovaTaskResponse) => updates.push(task.status))
    socket.subscribeQueue((stats) => queueStats.push(stats))

    // immediate push of current state after subscribe
    await waitFor(() => updates.includes('queued'))

    // REST upsert → WS broadcast to the frontend client
    await createTask(taskId, 'processing')
    await waitFor(() => updates.includes('processing'))
    await createTask(taskId, 'completed')
    await waitFor(() => updates.includes('completed'))

    // queueStatus immediate push
    await waitFor(() => queueStats.length > 0)
    expect(queueStats[0]).toHaveProperty('acceptingNewTasks', true)

    // heartbeat: connection must survive one full client ping cycle (25s)
    const wsBefore = socket.ws
    expect(wsBefore?.readyState).toBe(1) // OPEN
    await new Promise((r) => setTimeout(r, 27000))
    expect(socket.ws?.readyState).toBe(1) // still OPEN after ~1 heartbeat cycle
    expect(socket.ws).toBe(wsBefore) // same connection (no reconnect churn)
  }, 70000)

  it('disconnect → reconnect with re-subscribe', async () => {
    const taskId = 'fe-reconnect-' + Date.now()
    await createTask(taskId, 'queued')

    const updates: string[] = []
    socket.subscribeTask(taskId, (task: NovaTaskResponse) => updates.push(task.status))
    await waitFor(() => updates.includes('queued'))

    // force-close the underlying socket (simulates network drop; 4000 = app code)
    const ws = socket.ws
    expect(ws?.readyState).toBe(1)
    ws.close(4000, 'simulated drop')

    // client should reconnect (backoff 1s+) and re-subscribe → new immediate push
    await waitFor(() => updates.filter((s) => s === 'queued').length >= 2, 12000)
    expect(socket.ws?.readyState).toBe(1)

    // broadcast still works after reconnect
    await createTask(taskId, 'completed')
    await waitFor(() => updates.includes('completed'))
  }, 30000)
})
