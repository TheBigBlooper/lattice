import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

/**
 * Vite configuration for the status console.
 *
 * The dev server binds 5173 per the UI protocol. The build emits a static bundle that the console's
 * own container serves; nothing here is environment-specific, because config reaches the app through
 * `VITE_*` variables read at build time rather than through this file.
 *
 * Test configuration lives in `vitest.config.ts`, separately: Vite's own config type does not carry
 * the test block, so folding them together only type-checks by loosening the type.
 */
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
});
