import { get, set } from 'idb-keyval'
import type { ChatSession } from '@/types/chat'

const SESSION_KEY = 'eon.chat.sessions'
const MAX_SESSIONS = 20

export async function readSessions(): Promise<ChatSession[]> {
  try {
    const stored = await get<ChatSession[]>(SESSION_KEY)
    return Array.isArray(stored) ? stored : []
  } catch {
    return []
  }
}

export async function writeSessions(sessions: ChatSession[]): Promise<void> {
  try {
    // Pinia 状态是响应式 Proxy，IndexedDB 无法结构化克隆，先转为普通对象
    const plain = JSON.parse(JSON.stringify(sessions.slice(0, MAX_SESSIONS))) as ChatSession[]
    await set(SESSION_KEY, plain)
  } catch {
    // IndexedDB 不可用时静默降级为仅内存历史
  }
}
