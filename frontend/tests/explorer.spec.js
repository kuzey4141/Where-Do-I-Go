import { test, expect } from '@playwright/test'

// Synthetic API fixtures verify interaction, not actual travel requirements.
const countries = [
  { isoCode: 'DE', name: 'Almanya', summary: 'Almanya için örnek ülke tanıtımı. Test verisidir.' },
  { isoCode: 'JP', name: 'Japonya', summary: 'Japonya için örnek ülke tanıtımı. Test verisidir.' },
  { isoCode: 'TD', name: 'Çad', summary: 'Çad için örnek ülke tanıtımı. Test verisidir.' },
  { isoCode: 'TR', name: 'Türkiye', summary: 'Türkiye için örnek ülke tanıtımı. Test verisidir.' },
]
const passports = [
  { code: 'ORDINARY', name: 'Umuma Mahsus Pasaport', color: 'Bordo' },
  { code: 'SPECIAL', name: 'Hususi Pasaport', color: 'Yeşil' },
  { code: 'SERVICE', name: 'Hizmet Pasaportu', color: 'Gri' },
  { code: 'DIPLOMATIC', name: 'Diplomatik Pasaport', color: 'Siyah' },
]
function rulesFor(code) {
  if (code === 'TR') return []
  return passports.map((passport, index) => ({
    country: countries.find((country) => country.isoCode === code),
    passportType: passport.code,
    visaStatus: code === 'DE' ? (index === 0 ? 'VISA_REQUIRED' : 'VISA_FREE') : 'UNKNOWN',
    stayLimit: code === 'DE' && index > 0 ? 'Test dönemi içinde 90 gün' : null,
    conditions: 'Yalnızca arayüz testinde kullanılan örnek koşullar.',
    sourceUrl: 'https://example.test/source',
    verifiedAt: code === 'DE' ? '2026-10-28' : null,
  }))
}
async function mockApi(page) {
  await page.route('**/api/countries', (route) => route.fulfill({ json: countries }))
  await page.route('**/api/passport-types', (route) => route.fulfill({ json: passports }))
  await page.route('**/api/countries/*/entry-rules', (route) => {
    const code = new URL(route.request().url()).pathname.split('/')[3]
    return route.fulfill({ json: rulesFor(code) })
  })
}
async function openApp(page) {
  await mockApi(page)
  await page.goto('/')
  await expect(page.locator('.globe-renderer')).toHaveClass(/--ready/)
  await expect(page.getByRole('combobox')).toBeEnabled()
}
async function selectCountry(page, name) {
  await page.getByRole('combobox').fill(name)
  await page.keyboard.press('Enter')
  await expect(page.locator('.country-panel')).toHaveClass(/is-visible/)
  await expect(page.locator('.country-heading h1')).toHaveText(name)
}
async function centerOfGlobe(page) {
  const box = await page.locator('canvas').boundingBox()
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 }
}

test('selection shifts the globe and passport controls update actual displayed data', async ({ page }) => {
  await openApp(page)
  const before = await centerOfGlobe(page)
  await selectCountry(page, 'Almanya')
  await expect(page.locator('.visa-status h3')).toHaveText('Vize gerekli')
  expect((await centerOfGlobe(page)).x).toBeLessThan(before.x - 100)
  await page.getByRole('button', { name: 'Yeşil pasaport', exact: true }).click()
  await expect(page.locator('.visa-status h3')).toHaveText('Vizesiz')
  await expect(page.locator('.stay-value')).toHaveText('Test dönemi içinde 90 gün')
  await expect(page.locator('.about-section')).toContainText(countries[0].summary)
  await page.getByRole('button', { name: 'Ülke bilgilerini kapat' }).click()
  await expect(page.locator('.app')).not.toHaveClass(/is-selected/)
})

test('hover previews and animates the actual country border, then clicking opens that country', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'no-preference' })
  await openApp(page)
  const center = await centerOfGlobe(page)
  await page.mouse.move(center.x, center.y)
  await expect(page.locator('.country-preview h2')).toHaveText('Çad')
  await page.waitForTimeout(1500)
  const before = await page.locator('canvas').screenshot()
  await page.waitForTimeout(320)
  const after = await page.locator('canvas').screenshot()
  expect(before.equals(after)).toBe(false)
  await page.mouse.click(center.x, center.y)
  await expect(page.locator('.country-heading h1')).toHaveText('Çad')
  await expect(page.locator('.stay-value')).toHaveText('Belirtilmemiş')
  await expect(page.locator('.stay-note')).toHaveText('Süresiz kalış anlamına gelmez.')
})

test('drag does not select a country and wheel zoom clears stale hover', async ({ page }) => {
  await openApp(page)
  const center = await centerOfGlobe(page)
  await page.mouse.move(center.x, center.y)
  await expect(page.locator('.country-preview')).toBeVisible()
  await page.mouse.wheel(0, -180)
  await expect(page.locator('.country-preview')).toHaveCount(0)
  await page.mouse.move(center.x, center.y)
  await page.mouse.down()
  await page.mouse.move(center.x + 100, center.y + 30, { steps: 10 })
  await page.mouse.up()
  await expect(page.locator('.app')).not.toHaveClass(/is-selected/)
  await page.getByRole('button', { name: 'Dünyaya yaklaş' }).click()
  await page.getByRole('button', { name: 'Dünyadan uzaklaş' }).click()
  await page.getByRole('button', { name: 'Küre görünümünü sıfırla' }).click()
  await expect(page.locator('.globe-renderer')).toHaveClass(/--ready/)
})

test('a failed rules request is recoverable without losing the selected country', async ({ page }) => {
  await openApp(page)
  let first = true
  await page.route('**/api/countries/DE/entry-rules', (route) => {
    if (first) {
      first = false
      return route.fulfill({ status: 503, json: { error: 'Synthetic failure' } })
    }
    return route.fulfill({ json: rulesFor('DE') })
  })
  await selectCountry(page, 'Almanya')
  await expect(page.locator('.entry-error')).toBeVisible()
  await page.locator('.entry-error').getByRole('button', { name: 'Tekrar dene' }).click()
  await expect(page.locator('.visa-status h3')).toHaveText('Vize gerekli')
  await expect(page.locator('.country-heading h1')).toHaveText('Almanya')
})

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test(`conditions disclosure follows rendered lines and resets on selection (${viewport.width}px)`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await openApp(page)
    const longText = 'Yalnızca test için yazılan uzun koşul açıklaması. '.repeat(30)
    const fittingText = 'Geçerli pasaport ve seyahat belgeleri gerekir. Başvurunun seyahatten önce tamamlanması önerilir. Güncel koşullar ve gerekli belgeler hakkında bilgi almak için ilgili ülkenin resmî kaynağını inceleyebilirsin.'
    const multilineText = 'Örnek bir.\nÖrnek iki.\nÖrnek üç.\nÖrnek dört.\nÖrnek beş.\nÖrnek altı.'
    expect(fittingText.length).toBeGreaterThan(190)
    expect(multilineText.length).toBeLessThan(190)
    await page.route('**/api/countries/DE/entry-rules', (route) => route.fulfill({
      json: rulesFor('DE').map((rule) => ({
        ...rule,
        conditions: rule.passportType === 'SPECIAL' ? fittingText
          : rule.passportType === 'SERVICE' ? multilineText : longText,
      })),
    }))
    await selectCountry(page, 'Almanya')
    const paragraph = page.locator('.conditions p')
    const toggle = page.locator('.disclosure-button')
    const collapsedHeight = await paragraph.evaluate((element) => element.clientHeight)
    const lineHeight = await paragraph.evaluate((element) => parseFloat(getComputedStyle(element).lineHeight))
    expect(collapsedHeight).toBeLessThanOrEqual(lineHeight * 4 + 1)
    await expect(toggle).toHaveText('Devamını göster')
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await toggle.scrollIntoViewIfNeeded()
    const before = await page.evaluate(() => ({ window: scrollY, panel: document.querySelector('.panel-scroll').scrollTop }))
    await toggle.click()
    await expect(toggle).toHaveText('Daha az göster')
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(await paragraph.evaluate((element) => element.clientHeight)).toBeGreaterThan(collapsedHeight)
    expect(await page.evaluate(() => ({ window: scrollY, panel: document.querySelector('.panel-scroll').scrollTop }))).toEqual(before)
    await toggle.click()
    await expect(toggle).toHaveText('Devamını göster')
    expect(await paragraph.evaluate((element) => element.clientHeight)).toBe(collapsedHeight)

    await toggle.click()
    await page.getByRole('button', { name: 'Yeşil pasaport', exact: true }).click()
    await expect(paragraph).toHaveText(fittingText)
    await expect(toggle).toHaveCount(0)
    await page.getByRole('button', { name: 'Gri pasaport', exact: true }).click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await selectCountry(page, 'Japonya')
    await expect(toggle).toHaveCount(0)
    await selectCountry(page, 'Almanya')
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })
}

test('mobile selection keeps the header, search and panel readable without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await openApp(page)
  await selectCountry(page, 'Japonya')
  const brand = await page.locator('.brand').boundingBox()
  const search = await page.locator('.search-wrapper').boundingBox()
  expect(brand.y + brand.height).toBeLessThan(search.y)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.locator('.country-panel').scrollIntoViewIfNeeded()
  await expect(page.locator('.country-heading h1')).toBeVisible()
})

test('domestic travel and the keyboard-accessible guide have dedicated states', async ({ page }) => {
  await openApp(page)
  await selectCountry(page, 'Türkiye')
  await expect(page.locator('.domestic-note')).toContainText('Başladığın yer.')
  await expect(page.locator('.stay-value')).toHaveCount(0)
  await page.getByRole('button', { name: 'NASIL ÇALIŞIR?' }).click()
  await expect(page.getByRole('dialog', { name: 'Merak ettiğin yerden başla.' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
})
