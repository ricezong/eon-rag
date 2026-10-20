import { defineStore } from 'pinia'
import { streamChat } from '@/lib/sse'
import { readSessions, writeSessions } from '@/lib/storage'
import type { Citation } from '@/types/api'
import type { ChatMessage, ChatSession } from '@/types/chat'

const MAX_SESSIONS = 20
const SCOPE_KEY = 'eon.chat.scope'

interface ChatState {
  sessions: ChatSession[]
  currentId: string | null
  messages: ChatMessage[]
  streaming: boolean
  activeCitation: number | null
  selectedKbIds: string[]
  hydrated: boolean
  lastError: string | null
}

let controller: AbortController | null = null
let abortRequested = false
let flushTimer: ReturnType<typeof setTimeout> | null = null
let pendingText = ''

function newId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function createSession(): ChatSession {
  const now = Date.now()
  return {
    id: newId(),
    title: '新对话',
    createdAt: now,
    updatedAt: now,
    messages: [],
  }
}

function titleFromQuestion(question: string): string {
  const text = question.replace(/\s+/g, ' ').trim()
  return text.length > 28 ? `${text.slice(0, 28)}…` : text
}

export const useChatStore = defineStore('chat', {
  state: (): ChatState => ({
    sessions: [],
    currentId: null,
    messages: [],
    streaming: false,
    activeCitation: null,
    selectedKbIds: [],
    hydrated: false,
    lastError: null,
  }),

  getters: {
    currentSession(state): ChatSession | undefined {
      return state.sessions.find((session) => session.id === state.currentId)
    },
    sortedSessions(state): ChatSession[] {
      return [...state.sessions].sort((a, b) => b.updatedAt - a.updatedAt)
    },
    lastAssistant(state): ChatMessage | undefined {
      for (let i = state.messages.length - 1; i >= 0; i -= 1) {
        if (state.messages[i].role === 'assistant') return state.messages[i]
      }
      return undefined
    },
    latestCitations(): Citation[] {
      return this.lastAssistant?.citations ?? []
    },
    hasMessages(state): boolean {
      return state.messages.length > 0
    },
  },

  actions: {
    async hydrate() {
      if (this.hydrated) return
      this.hydrated = true
      const stored = (await readSessions()).sort((a, b) => b.updatedAt - a.updatedAt)
      this.sessions = stored
      const scope = localStorage.getItem(SCOPE_KEY)
      if (scope) {
        try {
          const parsed = JSON.parse(scope) as unknown
          if (Array.isArray(parsed)) this.selectedKbIds = parsed.filter((id) => typeof id === 'string')
        } catch {
          // 忽略损坏的作用域配置
        }
      }
      if (stored.length > 0) {
        this.loadSession(stored[0].id)
      } else {
        this.currentId = null
        this.messages = []
        this.activeCitation = null
        this.lastError = null
      }
    },

    newSession() {
      if (this.streaming) this.stop()
      this.sessions = this.sessions.filter((session) => session.messages.length > 0)
      const session = createSession()
      this.sessions.unshift(session)
      this.currentId = session.id
      this.messages = session.messages
      this.activeCitation = null
      this.lastError = null
    },

    loadSession(id: string) {
      if (this.streaming) this.stop()
      const session = this.sessions.find((item) => item.id === id)
      if (!session) return
      this.currentId = id
      this.messages = session.messages
      this.activeCitation = null
      this.lastError = null
    },

    deleteSession(id: string) {
      const wasCurrent = this.currentId === id
      if (wasCurrent && this.streaming) this.stop()
      this.sessions = this.sessions.filter((session) => session.id !== id)

      if (wasCurrent) {
        const next = this.sortedSessions[0]
        if (next) {
          this.loadSession(next.id)
        } else {
          this.currentId = null
          this.messages = []
          this.activeCitation = null
          this.lastError = null
        }
      }
      void this.persist()
    },

    setScope(ids: string[]) {
      this.selectedKbIds = [...ids]
      try {
        localStorage.setItem(SCOPE_KEY, JSON.stringify(this.selectedKbIds))
      } catch {
        // 存储失败不影响本次会话
      }
    },

    toggleScope(id: string) {
      const next = this.selectedKbIds.includes(id)
        ? this.selectedKbIds.filter((item) => item !== id)
        : [...this.selectedKbIds, id]
      this.setScope(next)
    },

    setActiveCitation(index: number | null) {
      this.activeCitation = index
    },

    async send(question: string) {
      const text = question.trim()
      if (!text || this.streaming) return

      if (!this.currentId) this.newSession()
      const session = this.currentSession
      if (!session) return
      session.messages = this.messages

      if (!session.title || session.title === '新对话') {
        session.title = titleFromQuestion(text)
      }

      const timestamp = Date.now()
      this.messages.push({
        id: newId(),
        role: 'user',
        content: text,
        citations: [],
        status: 'done',
        createdAt: timestamp,
      })
      this.messages.push({
        id: newId(),
        role: 'assistant',
        content: '',
        citations: [],
        status: 'streaming',
        createdAt: timestamp,
      })

      // 取回响应式代理，流式增量才能触发视图更新
      const assistant = this.messages[this.messages.length - 1]
      this.streaming = true
      this.lastError = null
      this.activeCitation = null
      pendingText = ''
      abortRequested = false
      controller = new AbortController()

      const flush = () => {
        if (flushTimer) {
          clearTimeout(flushTimer)
          flushTimer = null
        }
        if (!pendingText) return
        assistant.content += pendingText
        pendingText = ''
      }

      const scheduleFlush = () => {
        if (flushTimer) return
        flushTimer = setTimeout(flush, 60)
      }

      const complete = (status: ChatMessage['status'], error?: string) => {
        flush()
        assistant.status = status
        if (error) assistant.error = error
        this.streaming = false
        if (error) this.lastError = error
        session.updatedAt = Date.now()
        if (this.currentId === session.id) {
          session.messages = this.messages
          void this.persist()
        }
      }

      try {
        await streamChat(
          { question: text, kbIds: [...this.selectedKbIds] },
          {
            onCitations: (citations) => {
              assistant.citations = citations
            },
            onMessage: (delta) => {
              pendingText += delta
              scheduleFlush()
            },
            onDone: () => complete('done'),
            onError: (message) => complete('error', message),
          },
          controller.signal,
        )
      } catch (error) {
        if (abortRequested) {
          complete('stopped')
        } else {
          const message = error instanceof Error ? error.message : '生成失败，请重试'
          complete('error', message)
        }
      } finally {
        controller = null
        if (flushTimer) {
          clearTimeout(flushTimer)
          flushTimer = null
        }
      }
    },

    stop() {
      abortRequested = true
      controller?.abort()
    },

    retry() {
      if (this.streaming) return
      const lastUserIndex = this.messages.map((message) => message.role).lastIndexOf('user')
      if (lastUserIndex < 0) return
      const question = this.messages[lastUserIndex].content
      this.messages.splice(lastUserIndex)
      void this.send(question)
    },

    async persist() {
      const session = this.currentSession
      if (session) {
        session.messages = this.messages
        session.updatedAt = Date.now()
      }
      const toStore = this.sessions
        .filter((item) => item.messages.length > 0 || item.id === this.currentId)
        .slice(0, MAX_SESSIONS)
      this.sessions = toStore
      await writeSessions(toStore)
    },
  },
})
