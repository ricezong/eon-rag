import { chromium } from 'playwright-core'

const WEB = process.env.WEB_URL || 'http://127.0.0.1:5174'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
const errors = []
page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`console: ${message.text()}`)
})

let failed = false
try {
  // 1. 缺少扩展名拦截（任意扩展名本身允许，由解析路由决定能力）
  await page.goto(`${WEB}/documents`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '上传文档' }).first().click()
  const upload = page.getByRole('dialog')
  await upload.locator('input[type="file"]').setInputFiles({
    name: 'malware',
    mimeType: 'application/octet-stream',
    buffer: Buffer.from('not a document'),
  })
  await page.getByText('格式不支持').waitFor({ timeout: 10_000 })
  console.log('[interaction] missing extension rejected')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)

  // 2. 停止生成
  await page.goto(`${WEB}/chat`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '新对话' }).last().click()
  await page.fill(
    '[data-testid="composer"]',
    '请详细总结知识库中所有文档的内容，分章节列出，并给出不少于 800 字的分析。',
  )
  await page.click('[data-testid="send"]')
  await page.waitForSelector('button[aria-label="停止生成"]', { timeout: 15_000 })
  await page.waitForTimeout(900)
  await page.click('button[aria-label="停止生成"]')
  await page.getByText('已停止生成。').waitFor({ timeout: 15_000 })
  await page.screenshot({ path: 'shots/e2e-chat-stopped.png' })
  console.log('[interaction] stop generation works')
} catch (error) {
  failed = true
  console.error(`[interaction] FAILED: ${error.message}`)
  await page.screenshot({ path: 'shots/e2e-interaction-failure.png' }).catch(() => {})
} finally {
  await browser.close()
  console.log(errors.length ? `[interaction] browser errors: ${errors.join(' | ')}` : '[interaction] browser errors: none')
  process.exitCode = failed ? 1 : 0
}
