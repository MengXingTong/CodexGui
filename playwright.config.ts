import {defineConfig, devices} from '@playwright/test';

export default defineConfig({
  testDir: 'src/test/browser',
  timeout: 30_000,
  expect: {timeout: 5_000},
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'line',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    ...devices['Desktop Chrome'],
    permissions: ['clipboard-read', 'clipboard-write'],
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'node tools/serve-preview.mjs',
    url: 'http://127.0.0.1:4173/tools/ui-preview.html',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
  projects: [{name: 'chromium', use: {browserName: 'chromium'}}],
});
