import { mkdir } from 'node:fs/promises'
import { chromium } from 'playwright-core'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5176'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const DOC_ID = process.env.DOC_ID || '00000101-1111-4111-8111-111111111111'

await mkdir('shots', { recursive: true })

const browser = await chromium.launch({
  executablePath: CHROME,
  headless: true,
})
const page = await browser.newPage({
  viewport: { width: 1440, height: 1000 },
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

await page.goto(`${BASE}/documents/${DOC_ID}`, { waitUntil: 'networkidle' })
await shot('chunks-overview')

await page.getByRole('button', { name: '查看子块' }).first().click()
await page.waitForTimeout(500)
await shot('chunks-children')

const checks = page.getByLabel(/选择第/)
if ((await checks.count()) >= 2) {
  await checks.nth(0).check()
  await checks.nth(1).check()
  await shot('chunks-merge-selected')

  await page.getByRole('button', { name: '合并选中' }).click()
  await page.getByRole('button', { name: '合并', exact: true }).click()
  await page.waitForTimeout(700)
  await shot('chunks-merged')
}

await page.getByRole('button', { name: '编辑父块' }).first().click()
await page.waitForTimeout(300)
await shot('chunks-parent-edit')

const parentContent = page.locator('#parent-content')
const original = await parentContent.inputValue()
await parentContent.fill(`${original}\n\n### 人工补充\n\n这是通过父块编辑器新增的说明。`)
await page.getByRole('button', { name: '保存父块' }).click()
await page.waitForTimeout(900)
await shot('chunks-parent-saved')

if ((await page.getByRole('button', { name: '查看子块' }).count()) > 0) {
  await page.getByRole('button', { name: '查看子块' }).first().click()
  await page.waitForTimeout(500)
}

const editButtons = page.getByRole('button', { name: '编辑或拆分子块' })
if ((await editButtons.count()) > 0) {
  await editButtons.first().click()
  await page.waitForTimeout(300)
  await shot('chunks-child-edit')

  const childContent = page.locator('#child-content')
  const length = (await childContent.inputValue()).length
  await childContent.evaluate((element, offset) => {
    element.focus()
    element.setSelectionRange(offset, offset)
    element.dispatchEvent(new Event('select', { bubbles: true }))
  }, Math.floor(length / 2))
  await page.waitForTimeout(250)
  await shot('chunks-child-split-preview')

  const splitButton = page.getByRole('button', { name: '拆分', exact: true })
  if (await splitButton.isEnabled()) {
    await splitButton.click()
    await page.waitForTimeout(800)
    await shot('chunks-child-split')
  }
}

console.log(`[errors] ${errors.length}`)
for (const error of errors) console.log(error)
await browser.close()
