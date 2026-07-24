import { test, expect } from '@playwright/test';

// Helper: mock all SnapAgent API endpoints
async function mockApi(page) {
  // Auth config
  await page.route('**/snap-agent/auth-config', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ authHeader: '', authCookie: 'token' }) });
  });

  // User info — authorized
  await page.route('**/snap-agent/user-info', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true,
        authorized: true,
        username: 'testuser',
        userId: 'u-123',
        activeProfiles: ['prod'],
        issueClosureEnabled: false,
      }) });
  });

  // Skills list
  await page.route('**/snap-agent/skills', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({
        skills: [
          { name: 'CodeReview', description: 'Review code quality', availability: 'AVAILABLE', source: 'custom',
            inputs: [{ key: 'message', type: 'string', label: 'Message', required: true }] },
          { name: 'BugFinder', description: 'Find bugs', availability: 'AVAILABLE', source: 'builtin' },
          { name: 'Offline', description: 'Missing dep', availability: 'UNAVAILABLE', source: 'custom', unavailableReason: 'dependency missing' },
        ],
      }) });
  });

  // Models
  await page.route('**/snap-agent/models', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ allowed: ['gpt-4', 'claude-3'], default: 'claude-3' }) });
  });

  // Anchor config
  await page.route('**/snap-agent/anchor/config', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ enabled: true, disabledPaths: [] }) });
  });

  // Issues (empty)
  await page.route('**/snap-agent/issues', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ issues: [] }) });
  });

  // Conversation (empty)
  await page.route('**/snap-agent/conversations*', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ conversationId: null, messages: [] }) });
  });
}

test.describe('SnapAgent UI — Skill List', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
    await page.goto('http://localhost:3999/index.html');
    // Wait for app to load skills
    await page.waitForSelector('#hostSkills li');
  });

  test('displays available skills in sidebar', async ({ page }) => {
    await expect(page.locator('#hostSkills li').first()).toContainText('CodeReview');
    await expect(page.locator('#builtinSkills li').first()).toContainText('BugFinder');
    await expect(page.locator('#skillCount')).toHaveText('2');
    await expect(page.locator('#hostCount')).toHaveText('1');
    await expect(page.locator('#builtinCount')).toHaveText('1');
  });

  test('shows letter icons for collapsed sidebar', async ({ page }) => {
    const icons = page.locator('#skillIcons li');
    await expect(icons).toHaveCount(2);
    await expect(icons.first()).toHaveText('C');
    await expect(icons.nth(1)).toHaveText('B');
  });

  test('clicking a skill selects it and updates top bar', async ({ page }) => {
    await page.locator('#hostSkills li').first().click();
    await expect(page.locator('#skillContext')).toContainText('CodeReview');
  });

  test('clicking skill detail button opens modal', async ({ page }) => {
    await page.locator('#hostSkills li .skill-detail-btn').first().click();
    await expect(page.locator('#skillDetailModal')).toBeVisible();
    await expect(page.locator('#skillDetailModal')).toContainText('CodeReview');
    await expect(page.locator('#skillDetailModal')).toContainText('Review code quality');
  });

  test('closing skill detail modal works', async ({ page }) => {
    await page.locator('#hostSkills li .skill-detail-btn').first().click();
    await expect(page.locator('#skillDetailModal')).toBeVisible();
    await page.locator('#skillDetailClose').click();
    await expect(page.locator('#skillDetailModal')).toBeHidden();
  });

  test('toggle disabled skills visibility', async ({ page }) => {
    // Disabled skills should be hidden by default
    await expect(page.locator('#disabledSkills')).toHaveCSS('display', 'none');
    // Click toggle button
    await page.locator('#toggleDisabledBtn').click();
    await expect(page.locator('#disabledSkills')).toHaveCSS('display', 'block');
    // Should show unavailable skill
    await expect(page.locator('#disabledSkills li')).toContainText('Offline');
    // Click again to hide
    await page.locator('#toggleDisabledBtn').click();
    await expect(page.locator('#disabledSkills')).toHaveCSS('display', 'none');
  });

  test('collapsing and expanding host section', async ({ page }) => {
    const header = page.locator('#hostSectionHeader');
    const list = page.locator('#hostSkills');

    // Initially expanded
    await expect(list).toHaveCSS('display', 'block');

    // Click to collapse
    await header.click();
    await expect(list).toHaveCSS('display', 'none');

    // Click to expand
    await header.click();
    await expect(list).toHaveCSS('display', 'block');
  });
});

test.describe('SnapAgent UI — Model Selector', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#modelSelect option');
  });

  test('loads models from API', async ({ page }) => {
    const options = page.locator('#modelSelect option');
    await expect(options).toHaveCount(2);
    await expect(options.first()).toHaveValue('gpt-4');
    await expect(options.nth(1)).toHaveValue('claude-3');
  });

  test('selects default model from API', async ({ page }) => {
    await expect(page.locator('#modelSelect')).toHaveValue('claude-3');
  });

  test('persists model selection in localStorage', async ({ page }) => {
    await page.locator('#modelSelect').selectOption('gpt-4');
    const stored = await page.evaluate(() => localStorage.getItem('snap-agent.model'));
    expect(stored).toBe('gpt-4');
  });

  test('restores cached model on reload', async ({ page }) => {
    await page.locator('#modelSelect').selectOption('gpt-4');
    await page.reload();
    await page.waitForSelector('#modelSelect option');
    await expect(page.locator('#modelSelect')).toHaveValue('gpt-4');
  });
});

test.describe('SnapAgent UI — Chat', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);

    // Mock the run creation endpoint — returns a taskId
    await page.route('**/snap-agent/runs', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ taskId: 'task-abc' }) });
      } else {
        route.continue();
      }
    });

    // Mock SSE stream endpoint
    await page.route('**/snap-agent/runs/*/stream', (route) => {
      const body =
        'event: thought\n' +
        'data: {"text":"Hello"}\n\n' +
        'event: thought\n' +
        'data: {"text":" world"}\n\n' +
        'event: done\n' +
        'data: {}\n\n';
      route.fulfill({ status: 200, contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' },
        body });
    });

    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
    // Select CodeReview skill
    await page.locator('#hostSkills li').first().click();
  });

  test('shows input area after selecting a skill', async ({ page }) => {
    await expect(page.locator('#inputArea')).toBeVisible();
  });

  test('sends message and receives streaming response', async ({ page }) => {
    const input = page.locator('#messageInput');
    await input.fill('Review this code');
    await page.locator('#runBtn').click();

    // Wait for the AI message to appear
    await page.waitForSelector('.msg-ai', { timeout: 5000 });
    // The streaming response should contain "Hello world"
    await expect(page.locator('.msg-ai').last()).toContainText('Hello');
  });
});

test.describe('SnapAgent UI — File Upload', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);

    // Mock upload endpoint
    await page.route('**/snap-agent/skills/upload', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ success: true, message: 'Skill uploaded' }) });
    });

    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
  });

  test('file upload input exists and accepts .md files', async ({ page }) => {
    const input = page.locator('#uploadInput');
    await expect(input).toHaveAttribute('accept', '.md,.zip');
  });

  test('folder upload input exists with webkitdirectory', async ({ page }) => {
    const input = page.locator('#uploadFolderInput');
    await expect(input).toHaveAttribute('webkitdirectory', '');
  });

  test('refresh button triggers skills reload', async ({ page }) => {
    // Track if /skills was called again after clicking refresh
    let skillsCallCount = 0;
    await page.route('**/snap-agent/skills', (route) => {
      skillsCallCount++;
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({
          skills: [
            { name: 'CodeReview', description: 'Review code quality', availability: 'AVAILABLE', source: 'custom' },
          ],
        }) });
    });

    // Reload to pick up the new route handler
    await page.reload();
    await page.waitForSelector('#hostSkills li');
    const initialCount = skillsCallCount;

    await page.locator('#refreshBtn').click();
    await page.waitForTimeout(500);
    expect(skillsCallCount).toBeGreaterThan(initialCount);
  });
});

test.describe('SnapAgent UI — Auth States', () => {
  test('shows auth prompt when user is not authorized', async ({ page }) => {
    await page.route('**/snap-agent/auth-config', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ authHeader: '', authCookie: '' }) });
    });

    await page.route('**/snap-agent/user-info', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ authenticated: true, authorized: false, message: 'Access denied' }) });
    });

    await page.route('**/snap-agent/skills', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ skills: [] }) });
    });

    await page.route('**/snap-agent/models', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ allowed: [], default: '' }) });
    });

    await page.goto('http://localhost:3999/index.html');
    await expect(page.locator('#authPrompt')).toBeVisible();
    await expect(page.locator('.auth-title')).toHaveText('未授权');
    await expect(page.locator('.auth-msg')).toHaveText('Access denied');
  });

  test('shows login prompt when user is not authenticated', async ({ page }) => {
    await page.route('**/snap-agent/auth-config', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ authHeader: '', authCookie: '' }) });
    });

    await page.route('**/snap-agent/user-info', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ authenticated: false, authorized: false }) });
    });

    await page.route('**/snap-agent/skills', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ skills: [] }) });
    });

    await page.route('**/snap-agent/models', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ allowed: [], default: '' }) });
    });

    await page.goto('http://localhost:3999/index.html');
    await expect(page.locator('#authPrompt')).toBeVisible();
    await expect(page.locator('.auth-title')).toHaveText('未登录');
  });

  test('shows network error when server is unreachable', async ({ page }) => {
    await page.route('**/snap-agent/auth-config', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ authHeader: '', authCookie: '' }) });
    });

    await page.route('**/snap-agent/user-info', (route) => {
      route.abort();
    });

    await page.goto('http://localhost:3999/index.html');
    await expect(page.locator('#authPrompt')).toBeVisible();
    await expect(page.locator('.auth-title')).toHaveText('网络错误');
  });
});

test.describe('SnapAgent UI — Feature Navigation', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
  });

  test('shows feature navigation buttons', async ({ page }) => {
    await expect(page.locator('#navToolsBtn')).toBeVisible();
    await expect(page.locator('#navWorkflowsBtn')).toBeVisible();
    await expect(page.locator('#navCostBtn')).toBeVisible();
    await expect(page.locator('#navIssuesBtn')).toBeVisible();
    await expect(page.locator('#navPatrolBtn')).toBeVisible();
    await expect(page.locator('#navAlertsBtn')).toBeVisible();
    await expect(page.locator('#navKnowledgeBtn')).toBeVisible();
  });

  test('sidebar toggle collapses and expands', async ({ page }) => {
    const sidebar = page.locator('#sidebar');
    const toggle = page.locator('#sidebarToggle');
    // Click toggle to collapse
    await toggle.click();
    await expect(sidebar).toHaveClass(/collapsed/);
    // Click toggle to expand
    await toggle.click();
    await expect(sidebar).not.toHaveClass(/collapsed/);
  });
});
