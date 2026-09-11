import { test, expect } from '@playwright/test';

// Reuse the base mockApi helper — mocks auth, skills, models, etc.
async function mockBaseApi(page) {
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
          { name: 'log-analysis', description: 'Analyze logs', availability: 'AVAILABLE', source: 'custom',
            inputs: [{ key: 'query', type: 'string', label: 'Query', required: true }] },
          { name: 'send-alert', description: 'Send alert notification', availability: 'AVAILABLE', source: 'builtin',
            inputs: [{ key: 'message', type: 'string', label: 'Message', required: true }] },
          { name: 'disabled-skill', description: 'Not available', availability: 'UNAVAILABLE', source: 'custom',
            unavailableReason: 'Not configured', inputs: [] },
        ],
      }) });
  });

  await page.route('**/snap-agent/models', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ allowed: ['gpt-4', 'claude-3-haiku', 'claude-3-opus'], default: 'claude-3-haiku' }) });
  });

  await page.route('**/snap-agent/anchor/config', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ enabled: true, disabledPaths: [] }) });
  });

  await page.route('**/snap-agent/issues', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ issues: [] }) });
  });

  await page.route('**/snap-agent/conversations*', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ conversationId: null, messages: [] }) });
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

// Workflow designer API mocks
async function mockWorkflowDesignerApis(page, { workflows = [] } = {}) {
  // Designer-specific skill list endpoint
  await page.route('**/snap-agent/workflow-designer/skills', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([
        { name: 'log-analysis', description: 'Analyze log data',
          inputs: [{ key: 'query', type: 'string', label: 'Query', required: true }] },
        { name: 'send-alert', description: 'Send alert notification',
          inputs: [{ key: 'message', type: 'string', label: 'Message', required: true }] },
      ]) });
  });

  // Saved workflows list / create
  await page.route('**/snap-agent/workflow-designer/workflows', (route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(workflows) });
    } else if (route.request().method() === 'POST') {
      route.fulfill({ status: 201, contentType: 'application/json',
        body: JSON.stringify({ name: 'saved-wf', status: 'saved' }) });
    } else {
      route.continue();
    }
  });

  // Delete workflow by name
  await page.route('**/snap-agent/workflow-designer/workflows/*', (route) => {
    if (route.request().method() === 'DELETE') {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ deleted: true }) });
    } else if (route.request().method() === 'GET') {
      // GET single workflow
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ name: 'test-wf', description: '', steps: [] }) });
    } else {
      route.continue();
    }
  });
}

// Experiment API mocks
const MOCK_EXPERIMENTS = [
  {
    id: 'exp-1',
    name: 'Model Comparison',
    description: 'Compare GPT-4 vs Haiku',
    skillId: 'log-analysis',
    status: 'COMPLETED',
    variantCount: 2,
    createdAt: Date.now() - 60000,
    completedAt: Date.now() - 30000,
  },
];

function makeCompareResponse({ status = 'COMPLETED', bestVariant = 'haiku', variants } = {}) {
  return {
    experimentId: 'exp-1',
    status,
    bestVariant,
    variants: variants || [
      { name: 'gpt4', model: 'gpt-4', temperature: 0.7, success: true,
        durationMs: 2500, inputTokens: 1200, outputTokens: 800, cost: 0.05, iterationCount: 3 },
      { name: 'haiku', model: 'claude-3-haiku', temperature: 0.5, success: true,
        durationMs: 1200, inputTokens: 1200, outputTokens: 750, cost: 0.003, iterationCount: 2 },
    ],
  };
}

async function mockExperimentApis(page, { experiments = MOCK_EXPERIMENTS } = {}) {
  // List experiments
  await page.route('**/snap-agent/experiments', (route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ experiments, total: experiments.length }) });
    } else {
      route.continue();
    }
  });

  // Compare view for exp-1
  await page.route('**/snap-agent/experiments/exp-1/compare', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(makeCompareResponse()) });
  });

  // Run experiment
  await page.route('**/snap-agent/experiments/exp-1/run', (route) => {
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ id: 'exp-1', status: 'RUNNING', message: 'Experiment started' }) });
  });

  // Delete experiment
  await page.route('**/snap-agent/experiments/exp-1', (route) => {
    if (route.request().method() === 'DELETE') {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ id: 'exp-1', deleted: true }) });
    } else {
      route.continue();
    }
  });
}

// ===== WORKFLOW DESIGNER TESTS =====
test.describe('SnapAgent UI — Workflow Designer', () => {
  test.beforeEach(async ({ page }) => {
    await mockBaseApi(page);
    await mockWorkflowDesignerApis(page);
    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
  });

  test('opens designer modal when navDesignerBtn clicked', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });
  });

  test('shows modal title with 可视化 Skill 编排', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });
    const title = page.locator('#designerModal .modal-header h2');
    await expect(title).toContainText('可视化 Skill 编排');
  });

  test('loads and displays skill palette from workflow-designer/skills endpoint', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });

    // Wait for palette to populate after API call
    await page.waitForSelector('#designerPalette .designer-skill-card', { timeout: 5000 });

    const skillCards = page.locator('#designerPalette .designer-skill-card');
    await expect(skillCards).toHaveCount(2);

    // First skill card should show log-analysis
    const firstCard = skillCards.first();
    await expect(firstCard.locator('.skill-name')).toHaveText('log-analysis');
    await expect(firstCard.locator('.skill-desc')).toHaveText('Analyze log data');
  });

  test('skill cards are draggable (draggable=true attribute)', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('#designerPalette .designer-skill-card', { timeout: 5000 });

    const card = page.locator('#designerPalette .designer-skill-card').first();
    await expect(card).toHaveAttribute('draggable', 'true');
  });

  test('renders canvas with empty state initially', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('#designerPalette .designer-skill-card', { timeout: 5000 });

    // Canvas should show empty state prompt
    const canvas = page.locator('#designerCanvas');
    await expect(canvas).toBeVisible();
    const canvasText = await canvas.textContent();
    expect(canvasText).toMatch(/拖拽|添加步骤|empty|Empty/);
  });

  test('name and description inputs are present in toolbar', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });

    const nameInput = page.locator('#designerNameInput');
    await expect(nameInput).toBeVisible();
    await expect(nameInput).toHaveAttribute('placeholder', /名称|name/i);

    const descInput = page.locator('#designerDescInput');
    await expect(descInput).toBeVisible();
    await expect(descInput).toHaveAttribute('placeholder', /描述|description/i);
  });

  test('name input accepts text', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });

    await page.locator('#designerNameInput').fill('my-test-workflow');
    await expect(page.locator('#designerNameInput')).toHaveValue('my-test-workflow');
  });

  test('toolbar contains Export, Save, and Run buttons', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });

    await expect(page.locator('.designer-export-btn')).toBeVisible();
    await expect(page.locator('.designer-save-btn')).toBeVisible();
    await expect(page.locator('.designer-run-btn')).toBeVisible();
  });

  test('export button text contains 导出 YAML', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });

    await expect(page.locator('.designer-export-btn')).toContainText('导出 YAML');
  });

  test('properties panel shows placeholder when no step selected', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('#designerPalette .designer-skill-card', { timeout: 5000 });

    const propsPanel = page.locator('#designerProperties');
    await expect(propsPanel).toBeVisible();
    const propsText = await propsPanel.textContent();
    expect(propsText).toMatch(/属性|选择一个步骤|select.*step/i);
  });

  test('saved workflow dropdown loads from API', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });

    // The load section contains a select element
    const select = page.locator('#designerLoadSelect');
    await expect(select).toBeVisible();
    // Default option should be present
    await expect(select.locator('option').first()).toContainText('选择已保存的工作流');
  });

  test('loads saved workflows into dropdown', async ({ page }) => {
    // Re-setup with pre-existing workflows
    await page.route('**/snap-agent/workflow-designer/workflows', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify([
          { name: 'existing-wf', steps: [{ name: 's1', skill: 'log-analysis' }] },
        ]) });
    });

    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });

    // Wait for dropdown to populate
    await page.waitForTimeout(500);

    const select = page.locator('#designerLoadSelect');
    const options = await select.locator('option').allTextContents();
    // Should have the default option plus the saved workflow
    expect(options.length).toBeGreaterThanOrEqual(2);
    expect(options.some(o => o.includes('existing-wf'))).toBe(true);
  });

  test('double-click on skill card adds step to canvas', async ({ page }) => {
    await page.locator('#navDesignerBtn').click();
    await expect(page.locator('#designerModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('#designerPalette .designer-skill-card', { timeout: 5000 });

    // Double-click on log-analysis card
    const card = page.locator('#designerPalette .designer-skill-card').first();
    await card.dblclick();
    await page.waitForTimeout(300);

    // Canvas should now show a step (no longer empty)
    const canvasText = await page.locator('#designerCanvas').textContent();
    // After adding a step, the empty placeholder should be replaced with step content
    expect(canvasText).toContain('log-analysis');
  });
});

// ===== A/B EXPERIMENTS TESTS =====
test.describe('SnapAgent UI — A/B Experiments', () => {
  test.beforeEach(async ({ page }) => {
    await mockBaseApi(page);
    await mockExperimentApis(page);
    await page.goto('http://localhost:3999/index.html');
    await page.waitForSelector('#hostSkills li');
  });

  test('opens experiments modal when navExperimentsBtn clicked', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
  });

  test('modal title shows A/B 实验', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    const title = page.locator('#experimentsModal .modal-header h2');
    await expect(title).toContainText('A/B 实验');
  });

  test('displays experiment list from API', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });

    // Wait for experiments to load
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    const cards = page.locator('.experiment-card');
    await expect(cards).toHaveCount(1);

    // Card should show experiment name and status
    const cardText = await cards.first().textContent();
    expect(cardText).toContain('Model Comparison');
    expect(cardText).toContain('COMPLETED');
  });

  test('experiment card shows skill ID and variant count', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    const cardText = await page.locator('.experiment-card').first().textContent();
    expect(cardText).toContain('log-analysis');
    expect(cardText).toContain('2'); // variantCount = 2
  });

  test('clicking experiment opens compare view via /compare endpoint', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    // Click on the experiment card
    await page.locator('.experiment-card').first().click();
    await page.waitForTimeout(1000);

    // Compare view should render a table
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    const table = page.locator('.experiment-compare-table');
    await expect(table).toBeVisible();

    // Table should have rows for each variant
    const rows = table.locator('tbody tr');
    await expect(rows).toHaveCount(2);
  });

  test('compare view shows best variant with trophy indicator', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    // Should show best variant indicator
    const body = page.locator('#experimentsBody');
    const bodyText = await body.textContent();
    expect(bodyText).toContain('🏆');
    expect(bodyText).toContain('haiku');
    expect(bodyText).toContain('成本最低');
  });

  test('compare view shows status header', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    const bodyText = await page.locator('#experimentsBody').textContent();
    expect(bodyText).toContain('实验状态');
    expect(bodyText).toContain('COMPLETED');
  });

  test('compare table shows duration in seconds', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    const tableText = await page.locator('.experiment-compare-table').textContent();
    // gpt4: 2500ms → "2.5s", haiku: 1200ms → "1.2s"
    expect(tableText).toContain('2.5s');
    expect(tableText).toContain('1.2s');
  });

  test('compare table shows token counts', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    const tableText = await page.locator('.experiment-compare-table').textContent();
    expect(tableText).toContain('1200'); // inputTokens
    expect(tableText).toContain('800');  // outputTokens for gpt4
    expect(tableText).toContain('750');  // outputTokens for haiku
  });

  test('compare table shows cost with dollar sign', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    const tableText = await page.locator('.experiment-compare-table').textContent();
    // cost rendered as $0.0500 and $0.0030
    expect(tableText).toMatch(/\$0\.0500|\$0\.05/);
    expect(tableText).toMatch(/\$0\.0030|\$0\.003/);
  });

  test('compare table shows success indicators', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    const tableText = await page.locator('.experiment-compare-table').textContent();
    // Both variants are successful → should show ✅
    const checkmarks = (tableText.match(/✅/g) || []).length;
    expect(checkmarks).toBe(2);
  });

  test('back button returns to experiment list', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    // Go to compare view
    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    // Click back button
    const backBtn = page.locator('#experimentsBody button:has-text("返回列表")');
    await backBtn.click();
    await page.waitForTimeout(500);

    // Should be back to experiment list
    await expect(page.locator('.experiment-card')).toHaveCount(1);
  });

  test('shows empty state when no experiments exist', async ({ page }) => {
    // Override with empty list
    await page.route('**/snap-agent/experiments', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ experiments: [], total: 0 }) });
    });

    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(1000);

    const bodyText = await page.locator('#experimentsBody').textContent();
    expect(bodyText).toContain('暂无实验');
  });

  test('empty state shows API usage hint', async ({ page }) => {
    await page.route('**/snap-agent/experiments', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ experiments: [], total: 0 }) });
    });

    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(1000);

    const bodyText = await page.locator('#experimentsBody').textContent();
    expect(bodyText).toContain('POST /experiments');
  });

  test('pending variants show 等待执行 placeholder', async ({ page }) => {
    // Setup a CREATED experiment with pending variants
    await page.route('**/snap-agent/experiments', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({
          experiments: [{
            id: 'exp-pending', name: 'Pending Test', description: '',
            skillId: 'log-analysis', status: 'CREATED', variantCount: 2,
            createdAt: Date.now(), completedAt: 0,
          }],
          total: 1,
        }) });
    });

    await page.route('**/snap-agent/experiments/exp-pending/compare', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({
          experimentId: 'exp-pending',
          status: 'CREATED',
          bestVariant: null,
          variants: [
            { name: 'v1', model: 'gpt-4', temperature: 0.7, success: false, pending: true },
            { name: 'v2', model: 'claude-3-haiku', temperature: 0.5, success: false, pending: true },
          ],
        }) });
    });

    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    // Click into compare view
    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    const tableText = await page.locator('.experiment-compare-table').textContent();
    expect(tableText).toContain('等待执行');
  });

  test('CREATED experiment shows run button in compare view', async ({ page }) => {
    await page.route('**/snap-agent/experiments', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({
          experiments: [{
            id: 'exp-run', name: 'Ready to Run', description: '',
            skillId: 'log-analysis', status: 'CREATED', variantCount: 2,
            createdAt: Date.now(), completedAt: 0,
          }],
          total: 1,
        }) });
    });

    await page.route('**/snap-agent/experiments/exp-run/compare', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({
          experimentId: 'exp-run',
          status: 'CREATED',
          bestVariant: null,
          variants: [
            { name: 'v1', model: 'gpt-4', temperature: 0.7, success: false, pending: true },
            { name: 'v2', model: 'haiku', temperature: 0.5, success: false, pending: true },
          ],
        }) });
    });

    await page.route('**/snap-agent/experiments/exp-run/run', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ id: 'exp-run', status: 'RUNNING', message: 'Experiment started' }) });
    });

    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    // Should show run button
    const runBtn = page.locator('#experimentsBody .marketplace-install-btn, #experimentsBody button:has-text("运行实验")');
    await expect(runBtn.first()).toBeVisible({ timeout: 3000 });
    await expect(runBtn.first()).toContainText('运行实验');
  });

  test('failed variant shows ❌ with error message', async ({ page }) => {
    await page.route('**/snap-agent/experiments/exp-1/compare', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({
          experimentId: 'exp-1',
          status: 'COMPLETED',
          bestVariant: 'haiku',
          variants: [
            { name: 'gpt4', model: 'gpt-4', temperature: 0.7, success: false,
              durationMs: 500, inputTokens: 100, outputTokens: 0, cost: 0.01,
              iterationCount: 1, error: 'LLM timeout' },
            { name: 'haiku', model: 'claude-3-haiku', temperature: 0.5, success: true,
              durationMs: 1200, inputTokens: 1200, outputTokens: 750, cost: 0.003, iterationCount: 2 },
          ],
        }) });
    });

    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    const tableText = await page.locator('.experiment-compare-table').textContent();
    expect(tableText).toContain('❌');
    expect(tableText).toContain('LLM timeout');
    expect(tableText).toContain('✅');
  });

  test('COMPLETED experiment does not show run button', async ({ page }) => {
    await page.locator('#navExperimentsBtn').click();
    await expect(page.locator('#experimentsModal')).toBeVisible({ timeout: 5000 });
    await page.waitForSelector('.experiment-card', { timeout: 5000 });

    await page.locator('.experiment-card').first().click();
    await page.waitForSelector('.experiment-compare-table', { timeout: 5000 });

    // Run button should NOT be present for COMPLETED experiments
    const runBtn = page.locator('#experimentsBody .marketplace-install-btn, #experimentsBody button:has-text("运行实验")');
    await expect(runBtn).toHaveCount(0);
  });
});
