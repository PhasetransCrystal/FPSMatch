// Run with Node.js and sharp on NODE_PATH. Only public, version-pinned sources are used.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const sharp = require('sharp');

const version = '0.468.0';
const base = `https://raw.githubusercontent.com/lucide-icons/lucide/${version}`;
const root = path.resolve(__dirname, '../src/main/resources/assets/fpsmatch');
const icons = ['map', 'users', 'sliders-horizontal', 'ellipsis', 'rotate-cw', 'x',
  'chevron-down', 'chevron-right', 'arrow-right-left', 'user-x', 'shopping-cart'];

async function download(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`${response.status}: ${url}`);
  return Buffer.from(await response.arrayBuffer());
}

async function main() {
  const licenseDir = path.join(root, 'licenses/lucide');
  const imageDir = path.join(root, 'textures/gui/competitive');
  await fs.mkdir(licenseDir, { recursive: true });
  await fs.mkdir(imageDir, { recursive: true });
  await fs.mkdir(path.join(licenseDir, 'svg'), { recursive: true });
  await fs.writeFile(path.join(licenseDir, 'LICENSE'), await download(`${base}/LICENSE`));
  await fs.writeFile(path.join(licenseDir, 'FEATHER-LICENSE'),
    await download('https://raw.githubusercontent.com/feathericons/feather/v4.29.2/LICENSE'));
  const records = [];
  for (const name of icons) {
    const url = `${base}/icons/${name}.svg`;
    const source = await download(url);
    const meta = await sharp(source).metadata();
    if (meta.format !== 'svg' || meta.width !== 24 || meta.height !== 24) {
      throw new Error(`Unexpected icon format: ${url}`);
    }
    const mask = await sharp(source, { density: 192 }).resize(48, 48).ensureAlpha()
      .extractChannel('alpha').toBuffer();
    const colored = await sharp({ create: { width: 48, height: 48, channels: 3,
      background: '#e7edf2' } }).joinChannel(mask).png().toBuffer();
    await fs.writeFile(path.join(licenseDir, 'svg', `${name}.svg`), source);
    await fs.writeFile(path.join(imageDir, `${name}.png`), colored);
    records.push({ name, source: url, sourceSha256: crypto.createHash('sha256').update(source).digest('hex'),
      texture: `textures/gui/competitive/${name}.png`, pngSha256: crypto.createHash('sha256').update(colored).digest('hex') });
  }
  await fs.writeFile(path.join(licenseDir, 'sources.json'), JSON.stringify({
    project: 'Lucide', version, license: 'ISC; Feather-derived portions MIT',
    licenseSource: `${base}/LICENSE`,
    featherLicenseSource: 'https://raw.githubusercontent.com/feathericons/feather/v4.29.2/LICENSE',
    adaptation: 'Rendered at 48x48; monochrome RGB #e7edf2; original alpha retained.', icons: records
  }, null, 2) + '\n');
  console.log(`Imported ${records.length} Lucide icons with sources, hashes, and license texts.`);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
