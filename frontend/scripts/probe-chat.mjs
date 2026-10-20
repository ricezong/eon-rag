const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'

const response = await fetch(`${BASE}/api/chat`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
  body: JSON.stringify({ question: '这些文档主要讲了什么？请简要概括。', kbIds: [] }),
  signal: AbortSignal.timeout(130_000),
})

if (!response.ok) {
  console.log(`HTTP ${response.status}: ${await response.text()}`)
  process.exit(1)
}

const reader = response.body.getReader()
const decoder = new TextDecoder()
let buffer = ''
let text = ''
let citations = 0
let events = []

const dispatch = (frame) => {
  let event = 'message'
  const data = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  const payload = data.join('\n')
  if (event === 'citations') {
    citations = JSON.parse(payload).length
    events.push(`citations=${citations}`)
  } else if (event === 'message') {
    text += payload
  } else {
    events.push(`${event}:${payload.slice(0, 120)}`)
  }
}

while (true) {
  const { value, done } = await reader.read()
  if (done) break
  buffer += decoder.decode(value, { stream: true })
  let index
  while ((index = buffer.indexOf('\n\n')) >= 0) {
    const frame = buffer.slice(0, index)
    buffer = buffer.slice(index + 2)
    if (frame.trim()) dispatch(frame)
  }
}

console.log(`events: ${events.join(' | ')}`)
console.log(`answer chars: ${text.length}`)
console.log(`answer preview: ${text.slice(0, 300).replace(/\n/g, ' ')}`)
