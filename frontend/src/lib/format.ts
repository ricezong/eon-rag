import type { DocumentStatus } from '@/types/api'

const relativeFormatter = new Intl.RelativeTimeFormat('zh-CN', { numeric: 'auto' })
const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return dateTimeFormatter.format(date)
}

export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—'
  const time = new Date(iso).getTime()
  if (Number.isNaN(time)) return '—'
  const diffSeconds = Math.round((time - Date.now()) / 1000)
  const abs = Math.abs(diffSeconds)
  if (abs < 60) return '刚刚'
  if (abs < 3600) return relativeFormatter.format(Math.round(diffSeconds / 60), 'minute')
  if (abs < 86400) return relativeFormatter.format(Math.round(diffSeconds / 3600), 'hour')
  if (abs < 86400 * 30) return relativeFormatter.format(Math.round(diffSeconds / 86400), 'day')
  return formatDateTime(iso)
}

export function fileExtension(name: string): string {
  const index = name.lastIndexOf('.')
  return index >= 0 ? name.slice(index + 1).toLowerCase() : ''
}

export function statusText(status: DocumentStatus): string {
  switch (status) {
    case 'PROCESSING':
      return '处理中'
    case 'CHUNKED':
      return '待向量化'
    case 'INDEXING':
      return '向量化中'
    case 'INDEX_FAILED':
      return '向量化失败'
    case 'COMPLETED':
      return '已完成'
    case 'NEEDS_OCR':
      return '需 OCR'
    case 'FAILED':
      return '失败'
    default:
      return status
  }
}

export function parseMeta(
  meta: string | Record<string, string> | null | undefined,
): Record<string, string> {
  if (!meta) return {}
  if (typeof meta === 'string') {
    try {
      const value = JSON.parse(meta) as unknown
      return value && typeof value === 'object' ? (value as Record<string, string>) : {}
    } catch {
      return {}
    }
  }
  return meta
}

export function metaTags(meta: Record<string, string>): string[] {
  const tags: string[] = []
  const start = meta.page_start
  const end = meta.page_end
  if (start) tags.push(end && end !== start ? `P${start}–${end}` : `P${start}`)
  if (meta.sheet_name) tags.push(`表 ${meta.sheet_name}`)
  if (meta.slide_number) tags.push(`第 ${meta.slide_number} 页`)
  if (meta.rows) tags.push(`${meta.rows} 行`)
  return tags
}

export function truncate(text: string, length: number): string {
  return text.length > length ? `${text.slice(0, length)}…` : text
}

export function ratioText(parent: number, child: number): string {
  if (!parent) return '—'
  return `1 : ${(child / parent).toFixed(1)}`
}
