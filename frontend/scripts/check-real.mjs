import { chromium } from 'playwright-core'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5173'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`console: ${message.text()}`)
})
page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))

await page.goto(`${BASE}/documents`, { waitUntil: 'networkidle' })
const rows = await page.locator('tbody tr').count()
console.log(`[real] document rows: ${rows}`)

const docId = await page.evaluate(async () => {
  const res = await fetch('/api/documents')
  const json = await res.json()
  return json.data[0].id
})
await page.goto(`${BASE}/documents/${docId}`, { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
const chunks = await page.locator('article').count()
const parentSection = await page.getByText('父块内容').count()
console.log(`[real] parent section: ${parentSection}, chunk cards: ${chunks}`)

await page.goto(`${BASE}/kbs`, { waitUntil: 'networkidle' })
const kbCards = await page.locator('article').count()
console.log(`[real] kb cards: ${kbCards}`)

await page.goto(`${BASE}/documents`, { waitUntil: 'networkidle' })
await page.getByRole('button', { name: '上传文档' }).click()
await page.locator('input[type="file"]').setInputFiles({
  name: 'smoke-upload.txt',
  mimeType: 'text/plain',
  buffer: Buffer.from('支付网关配置说明。'.repeat(30)),
})
await page.getByRole('button', { name: /开始上传/ }).click()
await page.waitForTimeout(1800)
const submitted = await page.getByText(/已提交/).count()
console.log(`[real] upload submitted markers: ${submitted}`)

const finishButton = page.getByRole('button', { name: '完成', exact: true })
if (await finishButton.count()) await finishButton.click()
await page.waitForTimeout(800)

const cleanup = await page.evaluate(async () => {
  const res = await fetch('/api/documents')
  const json = await res.json()
  const smoke = json.data.find((doc) => doc.fileName === 'smoke-upload.txt')
  if (smoke) {
    await fetch(`/api/documents/${smoke.id}`, { method: 'DELETE' })
    return `deleted ${smoke.id}`
  }
  return 'no smoke document'
})
console.log(`[real] cleanup: ${cleanup}`)

console.log(errors.length ? `[real] browser errors: ${errors.join(' | ')}` : '[real] browser errors: none')
await browser.close()
