import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

/**
 * Test configuration for the status console.
 *
 * Coverage floors match the Java side (90% lines, 80% branches) so one standard covers the repo
 * rather than each language setting its own bar. The entry point and the test harness are excluded:
 * `main.tsx` only mounts the app, and measuring the harness that measures the tests says nothing.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov"],
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/main.tsx", "src/test/**", "src/**/*.test.{ts,tsx}"],
      thresholds: { lines: 90, branches: 80 },
    },
  },
});
