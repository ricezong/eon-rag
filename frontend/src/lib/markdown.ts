import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: false,
})

md.inline.ruler.before('link', 'citation', (state, silent) => {
  const rest = state.src.slice(state.pos)
  const match = /^\[(\d{1,2})\]/.exec(rest)
  if (!match) return false

  // [1](url) 或 [1][ref] 是链接语法，不当作引用角标
  const after = rest.slice(match[0].length)
  if (after.startsWith('(') || after.startsWith('[')) return false

  if (!silent) {
    const token = state.push('citation', 'button', 0)
    token.content = match[1]
    token.attrSet('data-cite', match[1])
  }
  state.pos += match[0].length
  return true
})

md.renderer.rules.citation = (tokens, idx) => {
  const value = tokens[idx].content
  return `<button type="button" class="cite" data-cite="${value}">${value}</button>`
}

const defaultLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(text: string): string {
  if (!text) return ''
  return DOMPurify.sanitize(md.render(text), {
    ADD_ATTR: ['data-cite', 'target', 'rel'],
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form', 'input', 'textarea', 'select'],
  })
}

export function toPlainText(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[#>*_`~]/g, '')
    .replace(/\[(\d+)\]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/** 父块内容通常以章节标题开头，展示时避免与卡片标题重复 */
export function stripLeadingTitle(content: string, title?: string | null): string {
  if (!title) return content
  const normalized = content.replace(/^\s+/, '')
  const lines = normalized.split('\n')
  if (lines.length === 0) return content
  const firstLine = lines[0].replace(/^#{1,6}\s+/, '').trim()
  if (firstLine !== title.trim()) return content
  return lines.slice(1).join('\n').replace(/^\s*\n/, '')
}
