import { defineConfig, devices } from '@playwright/test';

const STATIC_DIR = '../../../main/resources/static/snap-agent';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    // Serve static files from the snap-agent directory and mock API endpoints
    baseURL: 'http://localhost:0',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  // Use a simple static server with API mocking via Playwright routes
  webServer: {
    command: 'npx -y serve -s ../../../main/resources/static/snap-agent -p 3999',
    url: 'http://localhost:3999',
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
