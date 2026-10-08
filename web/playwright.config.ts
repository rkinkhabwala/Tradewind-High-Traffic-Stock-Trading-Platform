import { defineConfig, devices } from '@playwright/test';

/**
 * Phase 2 acceptance tests (spec 9) against the RUNNING stack, not a dev server:
 *   scripts/compose.sh up -d --build --scale stream-service=3
 *   cd web && npm run e2e
 * Serial on purpose: one test kills a stream-service instance, and timings should not compete.
 */
export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.spec.ts',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],
  outputDir: 'test-results.nosync',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3001',
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
  },
});
