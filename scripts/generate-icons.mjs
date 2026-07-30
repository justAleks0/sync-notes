/**
 * Rasterises assets/icon.svg into every icon each client needs.
 *
 * One-off tooling, so the dependencies are not part of any client's package.json:
 *
 *   npm install --no-save sharp png-to-ico
 *   node scripts/generate-icons.mjs
 *
 * Re-run this after editing assets/icon.svg and commit the results.
 */
import { mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'
import pngToIco from 'png-to-ico'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const icon = join(root, 'assets', 'icon.svg')
const foreground = join(root, 'assets', 'icon-foreground.svg')
const iconRound = join(root, 'assets', 'icon-round.svg')
const monochrome = join(root, 'assets', 'icon-monochrome.svg')

const render = (src, size) =>
  sharp(src, { density: 600 }).resize(size, size, { fit: 'contain', background: '#00000000' }).png()

const write = async (src, size, dest) => {
  mkdirSync(dirname(dest), { recursive: true })
  await render(src, size).toFile(dest)
  console.log(`  ${size.toString().padStart(4)}px  ${dest.replace(root + '\\', '').replace(root + '/', '')}`)
}

// Android densities as multiples of mdpi.
const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 }

console.log('Website')
const web = join(root, 'Website', 'public')
await write(icon, 32, join(web, 'favicon-32.png'))
await write(icon, 180, join(web, 'apple-touch-icon.png'))

console.log('Desktop')
const desktopBuild = join(root, 'Computer Software', 'build')
await write(icon, 512, join(desktopBuild, 'icon.png'))

// Windows wants one .ico holding every size it might display, from the 16px tray
// entry up to the 256px view in Explorer.
const icoSizes = [16, 24, 32, 48, 64, 128, 256]
const icoBuffers = await Promise.all(icoSizes.map((s) => render(icon, s).toBuffer()))
const icoTemp = join(desktopBuild, '.ico-tmp')
mkdirSync(icoTemp, { recursive: true })
const icoParts = icoSizes.map((s, i) => {
  const p = join(icoTemp, `${s}.png`)
  writeFileSync(p, icoBuffers[i])
  return p
})
writeFileSync(join(desktopBuild, 'icon.ico'), await pngToIco(icoParts))
rmSync(icoTemp, { recursive: true, force: true })
console.log(`  ico     Computer Software/build/icon.ico (${icoSizes.join(', ')})`)

console.log('Android')
const res = join(root, 'Android App', 'app', 'src', 'main', 'res')
for (const [density, scale] of Object.entries(DENSITIES)) {
  // Adaptive-icon foreground lives on a 108dp canvas.
  await write(foreground, Math.round(108 * scale), join(res, `mipmap-${density}`, 'ic_launcher_foreground.png'))
  // Themed-icon layer, same 108dp canvas. Android keeps only its alpha.
  await write(monochrome, Math.round(108 * scale), join(res, `mipmap-${density}`, 'ic_launcher_monochrome.png'))
  // Legacy launcher icons for API 24-25, which predates adaptive icons: 48dp.
  // Both variants are required - the manifest references round, and on those
  // versions there is no adaptive icon to fall back to.
  await write(icon, Math.round(48 * scale), join(res, `mipmap-${density}`, 'ic_launcher.png'))
  await write(iconRound, Math.round(48 * scale), join(res, `mipmap-${density}`, 'ic_launcher_round.png'))
}

console.log('\nDone.')
