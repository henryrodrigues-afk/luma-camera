/* Small native-Android QA helper. Only targets an emulator, never a connected phone.
 * Usage: node scripts/verification/AndroidUi.cjs emulator-5580 dump
 *        node scripts/verification/AndroidUi.cjs emulator-5580 tap "Abrir todas as configurações"
 *        node scripts/verification/AndroidUi.cjs emulator-5580 screen camera
 *        node scripts/verification/AndroidUi.cjs emulator-5580 fixture
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { projectPaths } = require('../lib/ProjectPaths.cjs');
const serial = process.argv[2];
const action = process.argv[3];
if (!/^emulator-\d+$/.test(serial || '')) throw Error('Choose an emulator serial explicitly.');
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT ||
  path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk');
const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const output = projectPaths.uiDirectory;
fs.mkdirSync(output, { recursive: true });
function call(args, options = {}) {
  const result = spawnSync(adb, ['-s', serial, ...args], { timeout: 90000, maxBuffer: 32 * 1024 * 1024, windowsHide: true, ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw Error(String(result.stderr || result.stdout));
  return result.stdout;
}
const decode = value => value.replace(/&quot;/g, '"').replace(/&apos;/g, "'")
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
function dump() {
  call(['shell', 'uiautomator', 'dump', '/sdcard/luma-ui.xml']);
  const xml = call(['exec-out', 'cat', '/sdcard/luma-ui.xml']).toString('utf8');
  fs.writeFileSync(path.join(output, 'latest.xml'), xml);
  const nodes = [...xml.matchAll(/<node\b([^>]+)>/g)].map(match => {
    const attributes = {};
    for (const attribute of match[1].matchAll(/([\w-]+)="([^"]*)"/g)) attributes[attribute[1]] = decode(attribute[2]);
    return attributes;
  });
  return nodes;
}
function tap(label) {
  const node = dump().find(n => n.text === label || n['content-desc'] === label);
  if (!node) throw Error('Control missing: ' + label);
  const bounds = [...node.bounds.matchAll(/\d+/g)].map(x => Number(x[0]));
  if (bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) throw Error('Control not visible: ' + label);
  if (node.enabled === 'false') throw Error('Control disabled: ' + label);
  call(['shell', 'input', 'tap', String(Math.round((bounds[0] + bounds[2]) / 2)),
    String(Math.round((bounds[1] + bounds[3]) / 2))]);
  return { tapped: label, bounds: node.bounds };
}
switch (action) {
  case 'dump': console.log(JSON.stringify(dump().filter(n => n.text || n['content-desc']).map(n => ({
    text: n.text, description: n['content-desc'], bounds: n.bounds, enabled: n.enabled, checked: n.checked
  })), null, 2)); break;
  case 'tap': console.log(JSON.stringify(tap(process.argv[4]))); break;
  case 'screen': {
    const name = process.argv[4] || 'screen';
    if (!/^[a-z0-9-]+$/.test(name)) throw Error('Use a simple screenshot name.');
    const destination = path.join(output, name + '.png');
    fs.writeFileSync(destination, call(['exec-out', 'screencap', '-p']));
    console.log(destination);
    break;
  }
  case 'fixture': {
    const fixture = path.join(projectPaths.fixtureDirectory, 'LUMA_UI_FIXTURE.mp4');
    if (!fs.existsSync(fixture)) throw Error('Generate the 3-second synthetic MP4 at ' + fixture + '; see docs/validation/VALIDACAO.md.');
    call(['shell', 'run-as', 'com.lumacamera', 'mkdir', '-p', 'files/recordings']);
    // ADB transfers binary bytes without an extra shell or Windows text-pipeline quoting.
    const temporary = '/data/local/tmp/LUMA_UI_FIXTURE.mp4';
    const destination = 'files/recordings/LUMA_UI_FIXTURE.mp4';
    call(['push', fixture, temporary]);
    call(['shell', 'run-as', 'com.lumacamera', 'cp', temporary, destination]);
    const copied = call(['exec-out', 'run-as', 'com.lumacamera', 'cat', destination]);
    if (!copied.equals(fs.readFileSync(fixture))) throw Error('Synthetic recording copy differs from the fixture.');
    call(['shell', 'rm', temporary]);
    console.log('Synthetic private recording seeded.');
    break;
  }
  default: throw Error('Expected dump, tap, screen or fixture.');
}
