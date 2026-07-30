import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // Relative base so the same build can be loaded from file:// by the Electron desktop app.
  base: './',
})
