import { mkdir } from 'node:fs/promises'
import { chromium } from 'playwright-core'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'

await mkdir('shots', { recursive: true })

const browser = await chromium.launch({
  executablePath: CHROME,
  headless: true,
})
const page = await browser.newPage({
  viewport: { width: 1440, height: 960 },
  deviceScaleFactor: 1,
})

const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`console: ${message.text()}`)
})
page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))

async function shot(name) {
  await page.waitForTimeout(450)
  await page.screenshot({ path: `shots/${name}.png`, fullPage: false })
  console.log(`[shot] ${name}`)
}

await page.goto(`${BASE}/chat`, { waitUntil: 'networkidle' })
await shot('chat-empty')

await page.fill('[data-testid="composer"]', '如何配置支付网关？')
await page.click('[data-testid="send"]')
await page.waitForTimeout(1400)
await shot('chat-streaming')

await page.waitForTimeout(2200)
await shot('chat-done')

const cite = page.locator('.cite[data-cite="1"]').first()
if (await cite.count()) {
  await cite.click()
  await page.waitForTimeout(400)
  const expand = page.getByText('查看原文').first()
  if (await expand.count()) {
    await expand.click()
    await page.waitForTimeout(500)
  }
  await shot('chat-citation')
}

await page.goto(`${BASE}/documents`, { waitUntil: 'networkidle' })
await shot('documents')

await page.getByRole('button', { name: '上传文档' }).click()
await shot('upload-dialog')
await page.keyboard.press('Escape')
await page.waitForTimeout(250)

const firstDocId = await page.evaluate(async () => {
  const res = await fetch('/api/documents')
  const json = await res.json()
  return json.data[0].id
})
await page.goto(`${BASE}/documents/${firstDocId}`, { waitUntil: 'networkidle' })
await shot('document-detail')

await page.goto(`${BASE}/kbs`, { waitUntil: 'networkidle' })
await shot('kbs')

await page.getByRole('button', { name: '新建知识库' }).click()
await shot('kb-form')
await page.keyboard.press('Escape')
await page.waitForTimeout(250)

const kbId = await page.evaluate(async () => {
  const res = await fetch('/api/kbs')
  const json = await res.json()
  return json.data[0].id
})
await page.goto(`${BASE}/kbs/${kbId}`, { waitUntil: 'networkidle' })
await shot('kb-detail')

await page.setViewportSize({ width: 390, height: 844 })
await page.goto(`${BASE}/chat`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await shot('mobile-chat')

const mobileCite = page.locator('.cite[data-cite="1"]').first()
if (await mobileCite.count()) {
  await mobileCite.click()
  await page.waitForTimeout(600)
  await shot('mobile-citations')
}

await browser.close()

if (errors.length > 0) {
  console.log('[browser errors]')
  for (const message of errors) console.log(message)
  process.exitCode = 1
} else {
  console.log('[browser errors] none')
}
console.log('[shot] done')
