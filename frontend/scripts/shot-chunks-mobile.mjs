import { chromium } from 'playwright-core'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5176'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const DOC_ID = '00000101-1111-4111-8111-111111111111'

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 1,
})

const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`console: ${message.text()}`)
})
page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))

async function shot(name) {
  await page.waitForTimeout(400)
  await page.screenshot({ path: `shots/${name}.png`, fullPage: false })
  console.log(`[shot] ${name}`)
}

await page.goto(`${BASE}/documents/${DOC_ID}`, { waitUntil: 'networkidle' })
await page.getByRole('button', { name: '查看子块' }).first().click()
await shot('chunks-mobile')

await page.getByRole('button', { name: '编辑父块' }).first().click()
await shot('chunks-mobile-parent-edit')
await page.keyboard.press('Escape')

const overflow = await page.evaluate(
  () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
)
console.log(`[overflow] ${overflow}`)
console.log(`[errors] ${errors.length}`)
for (const error of errors) console.log(error)

await browser.close()
