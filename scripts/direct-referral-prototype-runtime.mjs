import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// Reuse the App's installed browser dependency; this backend adds no npm stack.
assert(process.env.DIRECT_REFERRAL_APP_REPO, 'Set DIRECT_REFERRAL_APP_REPO to the App checkout');
assert(process.env.DIRECT_REFERRAL_EVIDENCE_DIR, 'Set DIRECT_REFERRAL_EVIDENCE_DIR outside the repository');
const { chromium } = await import(pathToFileURL(path.join(process.env.DIRECT_REFERRAL_APP_REPO, 'node_modules/playwright/index.mjs')).href);
const directory = path.resolve(process.env.DIRECT_REFERRAL_EVIDENCE_DIR);
fs.mkdirSync(directory, { recursive: true });
const browser = await chromium.launch({ headless: true });
const observations = [];
try {
  for (const width of [320, 980]) {
    const page = await browser.newPage({ viewport: { width, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(pathToFileURL(path.resolve('docs/specs/direct-referral-rewards/interaction.html')).href);
    await page.locator('#example').click();
    await page.locator('#policy button[type=submit]').click();
    assert(await page.locator('#confirm').isVisible());
    assert.match(await page.locator('#preview').innerText(), /总比例 10%，USDT 60% \/ NEX 40%/);
    await page.locator('#cancel').click();
    assert.equal(await page.locator('input[name=purchaseRate]').inputValue(), '10');
    assert.equal(await page.locator('#confirm').isVisible(), false);
    await page.locator('#policy button[type=submit]').click();
    await page.locator('#approve').click();
    assert.match(await page.locator('#saveStatus').innerText(), /至少 8 字/);
    await page.locator('#reason').fill('隔离验收确认两类分成规则');
    await page.locator('#approve').click();
    assert.match(await page.locator('#saveStatus').innerText(), /未写入业务数据/);
    for (const state of ['empty', 'loading', 'error', 'default']) {
      await page.locator(`button[data-state=${state}]`).click();
      assert.equal(await page.locator('#list').getAttribute('aria-busy'), String(state === 'loading'));
      assert.equal(await page.locator('#retry').isVisible(), state === 'error');
      assert.equal(await page.locator('#list output').count(), state === 'default' ? 1 : 0);
    }
    await page.locator('button[data-state=error]').click();
    await page.locator('#retry').click();
    assert.match(await page.locator('#list output').innerText(), /60 USDT \+ 4,000 NEX/);
    const bounds = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, width: innerWidth }));
    assert(bounds.scrollWidth <= bounds.width, 'Horizontal overflow');
    assert.deepEqual(errors, []);
    const screenshot = path.join(directory, `prototype-${width}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    observations.push({ width, screenshot, bounds, errors, scenarios: ['confirm', 'cancel-preserves-draft', 'reason-required', 'simulation-only', 'four-states', 'retry', 'dual-asset'] });
    await page.close();
  }
} finally { await browser.close(); }
fs.writeFileSync(path.join(directory, 'prototype-evidence.json'), JSON.stringify({ at: new Date().toISOString(), source: path.resolve('docs/specs/direct-referral-rewards/interaction.html'), scope: 'design simulation only; no business API or wallet persistence', observations }, null, 2));
console.log('Prototype interactions passed at 320px and 980px; this is not financial acceptance.');
