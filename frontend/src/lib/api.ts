import type { ApiResponse } from '@/types/api'

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

function notifyUnauthorized(status: number) {
  if (status === 401) {
    window.dispatchEvent(new CustomEvent('eon:unauthorized'))
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...((init.headers as Record<string, string> | undefined) ?? {}),
    },
  })
  notifyUnauthorized(response.status)

  const text = await response.text()
  let payload: ApiResponse<T> | null = null
  if (text) {
    try {
      payload = JSON.parse(text) as ApiResponse<T>
    } catch {
      // 非 JSON 响应，按 HTTP 状态处理
    }
  }

  if (!response.ok || (payload && payload.code !== 'OK')) {
    throw new ApiError(
      response.status,
      payload?.code ?? `HTTP_${response.status}`,
      payload?.message ?? response.statusText ?? '请求失败',
    )
  }
  return payload?.data as T
}

export function apiGet<T>(path: string): Promise<T> {
  return request<T>(path)
}

export function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

export function apiPut<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

export function apiDelete(path: string): Promise<void> {
  return request<void>(path, { method: 'DELETE' })
}

export interface UploadOptions {
  kbIds: string[]
  parseMode?: string
  onProgress?: (percent: number) => void
}

export function uploadDocument(file: File, options: UploadOptions): Promise<string> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/documents')

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        options.onProgress?.(Math.round((event.loaded / event.total) * 100))
      }
    }

    xhr.onerror = () => reject(new ApiError(0, 'NETWORK', '网络连接失败'))
    xhr.onload = () => {
      let payload: ApiResponse<{ documentId: string }> | null = null
      try {
        payload = JSON.parse(xhr.responseText) as ApiResponse<{ documentId: string }>
      } catch {
        // 忽略非 JSON 响应
      }
      notifyUnauthorized(xhr.status)
      if (xhr.status < 200 || xhr.status >= 300 || !payload || payload.code !== 'OK') {
        reject(
          new ApiError(
            xhr.status,
            payload?.code ?? `HTTP_${xhr.status}`,
            payload?.message ?? '上传失败',
          ),
        )
        return
      }
      resolve(payload.data.documentId)
    }

    const form = new FormData()
    form.append('file', file)
    options.kbIds.forEach((id) => form.append('kbIds', id))
    if (options.parseMode) form.append('parseMode', options.parseMode)
    xhr.send(form)
  })
}
