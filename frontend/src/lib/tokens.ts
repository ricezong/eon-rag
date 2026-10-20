/** 与后端默认配置保持一致，仅用于前端提示与预校验。 */
export const PARENT_MAX_TOKENS = 1200
export const CHILD_MAX_TOKENS = 320
/** 人工合并允许的更大子块上限 */
export const CHILD_MERGE_MAX_TOKENS = 640

/**
 * 与后端降级估算一致的 Token 预估值：
 * CJK 字符按 1.5，其它字符按 0.25。精确值以服务端为准。
 */
export function estimateTokens(text: string): number {
  let tokens = 0
  for (let i = 0; i < text.length; i += 1) {
    tokens += text.charCodeAt(i) > 0x2e7f ? 1.5 : 0.25
  }
  return Math.ceil(tokens)
}

export function tokenTone(tokens: number, limit: number): string {
  if (tokens > limit) return 'text-danger'
  if (tokens > limit * 0.85) return 'text-warning'
  return 'text-ink-3'
}
