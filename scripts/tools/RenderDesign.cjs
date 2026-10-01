// Render the HTML review mockup. This output is not a screenshot or Android validation.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
let playwright;
try { playwright = require('playwright'); }
catch { playwright = require(path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')); }
const {projectPaths} = require('../lib/ProjectPaths.cjs');
const root = projectPaths.projectRoot;
const output = path.join(projectPaths.previewDirectory, 'luma-0.7-board.png');
(async () => {
  const browser = await playwright.chromium.launch({
    executablePath: process.env.LUMA_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true
  });
  try {
    const page = await browser.newPage({viewport: {width: 1264, height: 1450}, deviceScaleFactor: 1});
    await page.goto(pathToFileURL(path.join(root, 'design', 'archive', '0.7', 'luma-0.7.html')).href, {waitUntil: 'load'});
    await page.evaluate(() => document.fonts.ready);
    const errors = await page.evaluate(() => [...document.querySelectorAll('.device')]
      .filter(device => device.scrollWidth > device.clientWidth || device.scrollHeight > device.clientHeight)
      .map(device => device.id));
    if (errors.length) throw new Error(`Mockup device overflow: ${errors.join(', ')}`);
    fs.mkdirSync(path.dirname(output), {recursive: true});
    await page.screenshot({path: output, fullPage: true, animations: 'disabled'});
    console.log(output);
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
