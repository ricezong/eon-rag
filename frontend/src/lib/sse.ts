import type { Citation } from '@/types/api'

export interface ChatStreamPayload {
  question: string
  kbIds: string[]
}

export interface ChatStreamHandlers {
  onCitations?: (citations: Citation[]) => void
  onMessage?: (delta: string) => void
  onDone?: () => void
  onError?: (message: string) => void
}

/**
 * POST /api/chat 的 SSE 流读取。
 * 按空行切帧，解析 event: 与多行 data:；AbortError 由调用方处理。
 */
export async function streamChat(
  payload: ChatStreamPayload,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  }

  const response = await fetch('/api/chat', {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
    signal,
  })

  if (response.status === 401) {
    window.dispatchEvent(new CustomEvent('eon:unauthorized'))
  }
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const data = (await response.json()) as { message?: string }
      if (data?.message) message = data.message
    } catch {
      // 保留默认提示
    }
    throw new Error(message)
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let finished = false

  const dispatch = (frame: string) => {
    const lines = frame.split(/\r?\n/)
    let event = 'message'
    const dataLines: string[] = []
    for (const line of lines) {
      if (!line || line.startsWith(':')) continue
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    const data = dataLines.join('\n')

    if (event === 'citations') {
      try {
        handlers.onCitations?.(JSON.parse(data) as Citation[])
      } catch {
        // 引用载荷异常时忽略，不影响正文
      }
    } else if (event === 'message') {
      if (data) handlers.onMessage?.(data)
    } else if (event === 'done') {
      finished = true
      handlers.onDone?.()
    } else if (event === 'error') {
      finished = true
      handlers.onError?.(data || '生成失败')
    }
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
      let index = buffer.indexOf('\n\n')
      while (index >= 0) {
        const frame = buffer.slice(0, index)
        buffer = buffer.slice(index + 2)
        if (frame.trim()) dispatch(frame)
        index = buffer.indexOf('\n\n')
      }
    }
    buffer += decoder.decode()
    if (buffer.trim()) dispatch(buffer)
  } finally {
    reader.releaseLock()
  }

  if (!finished) handlers.onDone?.()
}
