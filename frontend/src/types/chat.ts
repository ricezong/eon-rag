import type { Citation } from './api'

export type ChatRole = 'user' | 'assistant'
export type ChatStatus = 'streaming' | 'done' | 'error' | 'stopped'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  citations: Citation[]
  status: ChatStatus
  error?: string
  createdAt: number
}

export interface ChatSession {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: ChatMessage[]
}
