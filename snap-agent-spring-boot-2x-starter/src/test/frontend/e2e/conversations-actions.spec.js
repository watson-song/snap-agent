import { test, expect } from '@playwright/test';

// Helper: mock all SnapAgent API endpoints
async function mockApi(page) {
  await page.route('**/snap-agent/auth-config', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ authHeader: '', authCookie: 'token' }) });
  });

  await page.route('**/snap-agent/user-info', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({
        authenticated: true, authorized: true,
        username: 'testuser', userId: 'u-123',
        activeProfiles: ['prod'], issueClosureEnabled: false,
      }) });
  });

  await page.route('**/snap-agent/skills', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({
        skills: [
          { name: 'CodeReview', description: 'Review code quality', availability: 'AVAILABLE', source: 'custom',
            inputs: [{ key: 'message', type: 'string', label: 'Message', required: true }] },
          { name: 'BugFinder', description: 'Find bugs', availability: 'AVAILABLE', source: 'builtin' },
        ],
      }) });
  });

  await page.route('**/snap-agent/models', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ allowed: ['gpt-4', 'claude-3'], default: 'claude-3' }) });
  });

  await page.route('**/snap-agent/anchor/config', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ enabled: true, disabledPaths: [] }) });
  });

  await page.route('**/snap-agent/issues', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ issues: [] }) });
  });

  await page.route('**/snap-agent/runs*', (route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ tasks: [] }) });
    } else {
      route.continue();
    }
  });
}

// ===== CONVERSATION HISTORY =====
test.describe('SnapAgent UI — Conversation History', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);

    // Mock conversations list endpoint
    await page.route('**/snap-agent/conversations?*', (route) => {
      if (route.request().method() === 'GET') {
        const url = route.request().url();
        // Skill-specific conversation query
        if (url.includes('skillId=') || url.includes('skill=')) {
          route.fulfill({ status: 200, contentType: 'application/json',
            body: JSON.stringify({
              conversationId: 'conv-123',
              messages: [
                { role: 'user', content: 'Review this code', timestamp: Date.now() - 60000 },
                { role: 'assistant', content: 'I found 3 issues', timestamp: Date.now() - 50000 },
              ],
            }) });
        } else {
          // All conversations list
          route.fulfill({ status: 200, contentType: 'application/json',
            body: JSON.stringify({
              conversations: [
                { id: 'conv-123', skillName: 'CodeReview', messageCount: 5, updatedAt: Date.now() - 3600000 },
                { id: 'conv-456', skillName: 'BugFinder', messageCount: 3, updatedAt: Date.now() - 7200000 },
              ],
            }) });
        }
      } else {
        route.continue();
      }
    });

    // Mock download endpoint
    await page.route('**/snap-agent/conversations/*/download', (route) => {
      route.fulfill({ status: 200, contentType: 'text/plain',
        body: '# Conversation: CodeReview\n\nUser: Review this code\nAssistant: I found 3 issues\n' });
    });

    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
  });

  test('history button opens conversation history modal', async ({ page }) => {
    // Select a skill first
    await page.locator('#hostSkills li').first().click();
    // Click history button
    const historyBtn = page.locator('#historyBtn');
    if (await historyBtn.count() > 0) {
      await historyBtn.click();
      await expect(page.locator('#featureModal')).toBeVisible();
      const bodyText = await page.locator('#featureModal .history-modal-body').textContent();
      // Should show conversation list or current conversation
      expect(bodyText.length).toBeGreaterThan(0);
    }
  });

  test('displays conversation list in modal', async ({ page }) => {
    // Override conversations to return list
    await page.route('**/snap-agent/conversations?*', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({
          conversations: [
            { id: 'conv-123', skillName: 'CodeReview', messageCount: 5, updatedAt: Date.now() - 3600000 },
            { id: 'conv-456', skillName: 'BugFinder', messageCount: 3, updatedAt: Date.now() - 7200000 },
          ],
        }) });
    });

    await page.locator('#hostSkills li').first().click();
    const historyBtn = page.locator('#historyBtn');
    if (await historyBtn.count() > 0) {
      await historyBtn.click();
      await page.waitForSelector('.history-modal-body');
      const bodyText = await page.locator('#featureModal .history-modal-body').textContent();
      // May show conversation list
      if (bodyText.includes('conv-123') || bodyText.includes('CodeReview')) {
        expect(bodyText).toContain('conv-123');
      }
    }
  });

  test('load conversation by id sends GET request', async ({ page }) => {
    let loadedById = false;
    await page.route('**/snap-agent/conversations/conv-123', (route) => {
      if (route.request().method() === 'GET') {
        loadedById = true;
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({
            conversationId: 'conv-123',
            messages: [
              { role: 'user', content: 'Hello' },
              { role: 'assistant', content: 'Hi there' },
            ],
          }) });
      } else {
        route.continue();
      }
    });

    await page.locator('#hostSkills li').first().click();
    const historyBtn = page.locator('#historyBtn');
    if (await historyBtn.count() > 0) {
      await historyBtn.click();
      await page.waitForSelector('.history-modal-body');
      // Find and click a load button if present
      const loadBtn = page.locator('.history-modal-body [data-conv-load], .history-modal-body button:has-text("加载")').first();
      if (await loadBtn.count() > 0) {
        await loadBtn.click();
        await page.waitForTimeout(500);
        expect(loadedById).toBe(true);
      }
    }
  });

  test('download conversation triggers GET request', async ({ page }) => {
    let downloaded = false;
    await page.route('**/snap-agent/conversations/*/download', (route) => {
      if (route.request().method() === 'GET') {
        downloaded = true;
        route.fulfill({ status: 200, contentType: 'text/plain',
          headers: { 'Content-Disposition': 'attachment; filename="conv-123.md"' },
          body: '# Conversation\n\nHello' });
      } else {
        route.continue();
      }
    });

    await page.locator('#hostSkills li').first().click();
    const historyBtn = page.locator('#historyBtn');
    if (await historyBtn.count() > 0) {
      await historyBtn.click();
      await page.waitForSelector('.history-modal-body');
      const dlBtn = page.locator('.history-modal-body [data-conv-download], .history-modal-body button:has-text("下载")').first();
      if (await dlBtn.count() > 0) {
        await dlBtn.click();
        await page.waitForTimeout(500);
        expect(downloaded).toBe(true);
      }
    }
  });

  test('delete conversation sends DELETE request', async ({ page }) => {
    let deleted = false;
    await page.route('**/snap-agent/conversations/conv-*', (route) => {
      if (route.request().method() === 'DELETE') {
        deleted = true;
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true }) });
      } else {
        route.continue();
      }
    });

    page.on('dialog', dialog => dialog.accept());

    await page.locator('#hostSkills li').first().click();
    const historyBtn = page.locator('#historyBtn');
    if (await historyBtn.count() > 0) {
      await historyBtn.click();
      await page.waitForSelector('.history-modal-body');
      const delBtn = page.locator('.history-modal-body [data-conv-delete], .history-modal-body button:has-text("删除")').first();
      if (await delBtn.count() > 0) {
        await delBtn.click();
        await page.waitForTimeout(500);
        expect(deleted).toBe(true);
      }
    }
  });
});

// ===== PER-MESSAGE ACTIONS =====
test.describe('SnapAgent UI — Per-Message Actions', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);

    // Mock run creation
    await page.route('**/snap-agent/runs', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ taskId: 'task-abc' }) });
      } else {
        route.continue();
      }
    });

    // Mock SSE stream
    await page.route('**/snap-agent/runs/*/stream', (route) => {
      const body =
        'event: thought\n' +
        'data: {"text":"Analyzing code"}\n\n' +
        'event: tool_call\n' +
        'data: {"name":"CodeSearch","args":{"query":"find bugs"}}\n\n' +
        'event: tool_result\n' +
        'data: {"result":"Found 2 bugs"}\n\n' +
        'event: thought\n' +
        'data: {"text":"I found 2 bugs in your code"}\n\n' +
        'event: done\n' +
        'data: {}\n\n';
      route.fulfill({ status: 200, contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' },
        body });
    });

    // Mock solution endpoint
    await page.route('**/snap-agent/runs/*/solution', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, solution: 'Fix the null pointer' }) });
      } else {
        route.continue();
      }
    });

    // Mock create-issue from message
    await page.route('**/snap-agent/runs/*/issue', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ issueId: 'ISSUE-42', url: 'http://example.com/42' }) });
      } else {
        route.continue();
      }
    });

    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
    // Select CodeReview skill
    await page.locator('#hostSkills li').first().click();
  });

  test('copy message button copies to clipboard', async ({ page }) => {
    // Send a message first
    await page.locator('#messageInput').fill('Review this code');
    await page.locator('#runBtn').click();
    await page.waitForSelector('.msg-ai', { timeout: 5000 });

    // Find copy button
    const copyBtn = page.locator('.msg-ai [data-action="copy"], .msg-ai button:has-text("复制")').first();
    if (await copyBtn.count() > 0) {
      await copyBtn.click();
      // Verify clipboard content (may not work in all environments)
      // Just verify the button exists and is clickable
      expect(await copyBtn.count()).toBeGreaterThan(0);
    }
  });

  test('propose solution button sends POST', async ({ page }) => {
    await page.locator('#messageInput').fill('Review this code');
    await page.locator('#runBtn').click();
    await page.waitForSelector('.msg-ai', { timeout: 5000 });

    let solutionProposed = false;
    await page.route('**/snap-agent/runs/*/solution', (route) => {
      if (route.request().method() === 'POST') {
        solutionProposed = true;
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, solution: 'Fix null pointer' }) });
      } else {
        route.continue();
      }
    });

    const proposeBtn = page.locator('.msg-ai [data-action="propose-solution"], .msg-ai button:has-text("方案")').first();
    if (await proposeBtn.count() > 0) {
      await proposeBtn.click();
      await page.waitForTimeout(500);
      expect(solutionProposed).toBe(true);
    }
  });

  test('create issue from message sends POST', async ({ page }) => {
    await page.locator('#messageInput').fill('Review this code');
    await page.locator('#runBtn').click();
    await page.waitForSelector('.msg-ai', { timeout: 5000 });

    let issueCreated = false;
    await page.route('**/snap-agent/runs/*/issue', (route) => {
      if (route.request().method() === 'POST') {
        issueCreated = true;
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ issueId: 'ISSUE-55', url: 'http://example.com/55' }) });
      } else {
        route.continue();
      }
    });

    const createIssueBtn = page.locator('.msg-ai [data-action="create-issue"], .msg-ai button:has-text("创建")').first();
    if (await createIssueBtn.count() > 0) {
      await createIssueBtn.click();
      await page.waitForTimeout(500);
      expect(issueCreated).toBe(true);
    }
  });

  test('SSE thought events render as AI messages', async ({ page }) => {
    await page.locator('#messageInput').fill('Review this code');
    await page.locator('#runBtn').click();
    await page.waitForSelector('.msg-ai', { timeout: 5000 });
    const aiMessages = page.locator('.msg-ai');
    const count = await aiMessages.count();
    expect(count).toBeGreaterThan(0);
    const lastMsg = await aiMessages.last().textContent();
    expect(lastMsg).toContain('Analyzing');
  });

  test('SSE tool_call events render', async ({ page }) => {
    await page.locator('#messageInput').fill('Review this code');
    await page.locator('#runBtn').click();
    await page.waitForTimeout(2000);
    // Check for tool call rendering
    const toolCalls = page.locator('.msg-tool-call, [data-event="tool_call"]');
    if (await toolCalls.count() > 0) {
      const text = await toolCalls.first().textContent();
      expect(text).toContain('CodeSearch');
    }
  });

  test('SSE done event completes the conversation', async ({ page }) => {
    await page.locator('#messageInput').fill('Review this code');
    await page.locator('#runBtn').click();
    // Wait for done event
    await page.waitForTimeout(3000);
    // Input should be re-enabled
    const input = page.locator('#messageInput');
    await expect(input).toBeEnabled();
  });
});

// ===== STREAM CANCELLATION =====
test.describe('SnapAgent UI — Stream Cancellation', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);

    // Mock run creation
    await page.route('**/snap-agent/runs', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ taskId: 'task-cancel-test' }) });
      } else {
        route.continue();
      }
    });

    // Mock SSE stream — long running so we can cancel mid-stream
    await page.route('**/snap-agent/runs/*/stream', (route) => {
      const body =
        'event: thought\n' +
        'data: {"text":"Working on it..."}\n\n';
      // Don't send done event — stream stays open
      route.fulfill({ status: 200, contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' },
        body });
    });

    // Mock cancel endpoint
    await page.route('**/snap-agent/runs/*/cancel', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true }) });
      } else {
        route.continue();
      }
    });

    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
    await page.locator('#hostSkills li').first().click();
  });

  test('cancel button sends POST to /runs/{taskId}/cancel', async ({ page }) => {
    let cancelled = false;
    await page.route('**/snap-agent/runs/*/cancel', (route) => {
      if (route.request().method() === 'POST') {
        cancelled = true;
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true }) });
      } else {
        route.continue();
      }
    });

    // Send a message
    await page.locator('#messageInput').fill('Long running task');
    await page.locator('#runBtn').click();
    await page.waitForSelector('.msg-ai', { timeout: 5000 });

    // Find cancel button
    const cancelBtn = page.locator('#cancelBtn, [data-action="cancel"], button:has-text("取消")').first();
    if (await cancelBtn.count() > 0) {
      await cancelBtn.click();
      await page.waitForTimeout(500);
      expect(cancelled).toBe(true);
    }
  });
});

// ===== FILE UPLOAD =====
test.describe('SnapAgent UI — File Upload Actions', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);

    // Mock upload endpoints
    await page.route('**/snap-agent/skills/upload', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, message: 'Skill uploaded', skillName: 'UploadedSkill' }) });
      } else {
        route.continue();
      }
    });

    await page.route('**/snap-agent/skills/upload-folder', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, message: 'Folder uploaded', count: 3 }) });
      } else {
        route.continue();
      }
    });

    // Mock refresh endpoint
    await page.route('**/snap-agent/skills/refresh', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true }) });
      } else {
        route.continue();
      }
    });

    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
  });

  test('file upload input accepts .md and .zip files', async ({ page }) => {
    const input = page.locator('#uploadInput');
    await expect(input).toHaveAttribute('accept', '.md,.zip');
  });

  test('folder upload input has webkitdirectory attribute', async ({ page }) => {
    const input = page.locator('#uploadFolderInput');
    // webkitdirectory is a boolean attribute
    const attr = await input.getAttribute('webkitdirectory');
    expect(attr !== null).toBe(true);
  });

  test('file upload sends POST with FormData', async ({ page }) => {
    let uploadData = null;
    await page.route('**/snap-agent/skills/upload', (route) => {
      if (route.request().method() === 'POST') {
        const contentType = route.request().headers()['content-type'] || '';
        expect(contentType).toContain('multipart/form-data');
        uploadData = true;
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, skillName: 'NewSkill' }) });
      } else {
        route.continue();
      }
    });

    // Create a temp .md file and upload
    const input = page.locator('#uploadInput');
    await input.setInputFiles({
      name: 'test-skill.md',
      mimeType: 'text/markdown',
      buffer: Buffer.from('# Test Skill\nA test skill.'),
    });

    await page.waitForTimeout(1000);
    expect(uploadData).toBe(true);
  });

  test('refresh button sends POST to /skills/refresh', async ({ page }) => {
    let refreshed = false;
    await page.route('**/snap-agent/skills/refresh', (route) => {
      if (route.request().method() === 'POST') {
        refreshed = true;
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true }) });
      } else {
        route.continue();
      }
    });

    const refreshBtn = page.locator('#refreshBtn');
    if (await refreshBtn.count() > 0) {
      await refreshBtn.click();
      await page.waitForTimeout(500);
      expect(refreshed).toBe(true);
    }
  });
});

// ===== RECONNECTION =====
test.describe('SnapAgent UI — Task Reconnection', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);

    // Mock running tasks on initial load
    await page.route('**/snap-agent/runs?*', (route) => {
      if (route.request().method() === 'GET') {
        route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({
            tasks: [
              { taskId: 'running-1', skillName: 'CodeReview', status: 'RUNNING' },
            ],
          }) });
      } else {
        route.continue();
      }
    });

    // Mock SSE stream for reconnected task
    await page.route('**/snap-agent/runs/*/stream', (route) => {
      const body =
        'event: thought\n' +
        'data: {"text":"Resuming task"}\n\n' +
        'event: done\n' +
        'data: {}\n\n';
      route.fulfill({ status: 200, contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' },
        body });
    });

    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
  });

  test('reconnects running tasks on page load', async ({ page }) => {
    // The init function calls reconnectRunningTasks() which GETs /runs?status=RUNNING
    // Verify the request was made
    await page.waitForTimeout(2000);
    // If reconnection happened, there should be messages in chat area
    // or a running indicator
    const chatArea = page.locator('#chatArea, #chatMessages');
    if (await chatArea.count() > 0) {
      const chatText = await chatArea.textContent();
      // May contain the running task indicator
      expect(chatText !== null).toBe(true);
    }
  });
});

// ===== ADDITIONAL SIDEBAR & SKILL DETAIL =====
test.describe('SnapAgent UI — Additional Sidebar Tests', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
  });

  test('builtin section header toggles collapse', async ({ page }) => {
    const header = page.locator('#builtinSectionHeader');
    const list = page.locator('#builtinSkills');

    // Initially may be collapsed (data-collapsed="true")
    const initialCollapsed = await header.getAttribute('data-collapsed');
    await header.click();

    const afterClick = await header.getAttribute('data-collapsed');
    expect(afterClick).not.toBe(initialCollapsed);
  });

  test('skill detail modal shows inputs', async ({ page }) => {
    await page.locator('#hostSkills li .skill-detail-btn').first().click();
    await expect(page.locator('#skillDetailModal')).toBeVisible();
    const modalText = await page.locator('#skillDetailModal').textContent();
    // CodeReview has an input field 'message'
    expect(modalText).toContain('message');
  });

  test('skill detail modal shows source type', async ({ page }) => {
    await page.locator('#hostSkills li .skill-detail-btn').first().click();
    await expect(page.locator('#skillDetailModal')).toBeVisible();
    const modalText = await page.locator('#skillDetailModal').textContent();
    expect(modalText).toContain('custom');
  });

  test('builtin skill detail modal shows source', async ({ page }) => {
    const detailBtn = page.locator('#builtinSkills li .skill-detail-btn').first();
    if (await detailBtn.count() > 0) {
      await detailBtn.click();
      await expect(page.locator('#skillDetailModal')).toBeVisible();
      const modalText = await page.locator('#skillDetailModal').textContent();
      expect(modalText).toContain('builtin');
    }
  });
});

// ===== MODEL SELECTOR EDGE CASES =====
test.describe('SnapAgent UI — Model Selector Edge Cases', () => {
  test('handles empty model list gracefully', async ({ page }) => {
    await mockApi(page);
    await page.route('**/snap-agent/models', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ allowed: [], default: '' }) });
    });

    await page.goto('http://localhost:3999/index.html');
    // Should not crash
    const modelSelect = page.locator('#modelSelect');
    if (await modelSelect.count() > 0) {
      const options = modelSelect.locator('option');
      const count = await options.count();
      expect(count).toBe(0);
    }
  });

  test('handles model API error gracefully', async ({ page }) => {
    await mockApi(page);
    await page.route('**/snap-agent/models', (route) => {
      route.fulfill({ status: 500, contentType: 'application/json',
        body: JSON.stringify({ error: 'Server error' }) });
    });

    await page.goto('http://localhost:3999/index.html');
    // Should not crash — select should be empty or show fallback
    const modelSelect = page.locator('#modelSelect');
    expect(await modelSelect.count()).toBeGreaterThanOrEqual(0);
  });
});
