import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The release script stamps package.json, so baking the version in here keeps the
// number shown in the UI correct without anyone maintaining it by hand.
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'))

export default defineConfig({
  plugins: [react()],
  // Relative base so the same build can be loaded from file:// by the Electron desktop app.
  base: './',
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
})
