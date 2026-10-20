export interface ApiResponse<T> {
  code: string
  message: string | null
  data: T
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export type DocumentStatus =
  | 'PROCESSING'
  | 'CHUNKED'
  | 'INDEXING'
  | 'INDEX_FAILED'
  | 'COMPLETED'
  | 'NEEDS_OCR'
  | 'FAILED'

export interface RagDocument {
  id: string
  fileName: string
  fileType: string
  fileSize: number | null
  status: DocumentStatus
  parseMode: 'AUTO' | 'LOCAL' | 'CLOUD' | string
  errorMessage: string | null
  parentCount: number
  childCount: number
  createdAt: string
  updatedAt: string
  kbIds?: string[]
}

export interface KnowledgeBase {
  id: string
  name: string
  description: string
  color: string
  documentCount?: number
  createdAt: string
  updatedAt: string
}

export interface ParentChunk {
  id: string
  documentId: string
  seq: number
  title: string | null
  content: string
  tokenCount: number
  meta: string | Record<string, string>
  createdAt: string
}

export interface ChildChunk {
  id: string
  parentId: string
  documentId: string
  seq: number
  content: string
  contextPrefix: string | null
  tokenCount: number
  meta: string | Record<string, string>
  createdAt: string
}

export interface CitationChild {
  childId: string
  seq: number
  score: number
}

export interface Citation {
  index: number
  title: string | null
  documentId: string
  parentId: string
  meta: Record<string, string>
  children: CitationChild[]
}
