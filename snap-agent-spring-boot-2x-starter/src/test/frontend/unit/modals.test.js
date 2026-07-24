import { describe, it, expect, beforeEach, vi } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';

const STATIC_DIR = resolve(__dirname, '../../../main/resources/static/snap-agent');
const appSource = readFileSync(resolve(STATIC_DIR, 'app.js'), 'utf-8');

// Load app.js utility functions (everything before the Init IIFE)
const utilitySource = appSource.split('// ===== Init =====')[0];
// eslint-disable-next-line no-eval
eval(utilitySource);

// Helper to set up DOM for modal tests
function setupModalDOM() {
  document.body.innerHTML = `
    <div id="featureModal" class="modal" style="display:none;">
      <div class="modal-backdrop"></div>
      <div class="modal-content">
        <div class="modal-header">
          <span class="history-modal-title"></span>
          <button id="featureModalClose" class="modal-close">×</button>
        </div>
        <div class="history-modal-body"></div>
      </div>
    </div>
    <div id="skillDetailModal" class="modal" style="display:none;"></div>
    <div id="authPrompt" style="display:none;"></div>
    <div id="chatArea"></div>
    <div id="chatMessages"></div>
    <span id="skillContext"></span>
    <span id="userInfo"></span>
    <ul id="hostSkills"></ul>
    <ul id="builtinSkills"></ul>
    <ul id="disabledSkills" style="display:none;"></ul>
    <ul id="skillIcons"></ul>
    <span id="skillCount"></span>
    <span id="hostCount"></span>
    <span id="builtinCount"></span>
    <select id="modelSelect"></select>
    <div id="inputArea" style="display:none;">
      <textarea id="messageInput"></textarea>
      <button id="runBtn">Run</button>
      <button id="historyBtn">History</button>
    </div>
    <button id="refreshBtn">Refresh</button>
    <button id="sidebarToggle"></button>
    <div id="sidebar"></div>
    <div id="alertBadge" style="display:none;"></div>
  `;
}

// Helper to mock the openFeatureModal function if not loaded
function ensureOpenFeatureModal() {
  if (typeof openFeatureModal !== 'function') {
    // eslint-disable-next-line no-eval
    eval(`
      function openFeatureModal(title, content) {
        var modal = document.getElementById('featureModal');
        if (!modal) {
          modal = document.createElement('div');
          modal.id = 'featureModal';
          modal.className = 'modal';
          modal.innerHTML = '<div class="modal-content"><div class="modal-header"><span class="history-modal-title"></span><button id="featureModalClose" class="modal-close">×</button></div><div class="history-modal-body"></div></div>';
          document.body.appendChild(modal);
        }
        modal.style.display = 'block';
        var titleEl = modal.querySelector('.history-modal-title');
        if (titleEl) titleEl.textContent = title || '';
        var body = modal.querySelector('.history-modal-body');
        if (body && content !== null && content !== undefined) {
          body.innerHTML = content;
        }
        return modal;
      }
      function featureEmpty(msg) {
        return '<div class="feature-empty">' + (msg || '') + '</div>';
      }
    `);
  }
}

describe('app.js — showToolsModal with mocked fetch', () => {
  beforeEach(() => {
    setupModalDOM();
    ensureOpenFeatureModal();
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('renders tools list from API response', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/tools') && !url.includes('/plugins')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            tools: [
              { name: 'CodeSearch', description: 'Search code', category: 'search', enabled: true },
              { name: 'FileWriter', description: 'Write files', category: 'io', enabled: false },
            ],
          }),
        });
      }
      if (url.includes('/tools/plugins')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            plugins: [{ name: 'git-plugin', version: '1.0', enabled: true }],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showToolsModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body).not.toBeNull();
    const bodyText = body.textContent;
    expect(bodyText).toContain('CodeSearch');
    expect(bodyText).toContain('FileWriter');
  });

  it('shows empty state when no tools available', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/tools') && !url.includes('/plugins')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ tools: [] }),
        });
      }
      if (url.includes('/tools/plugins')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ plugins: [] }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showToolsModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('无');
  });

  it('shows error message when API fails', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('Network error'));

    await showToolsModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('失败');
  });
});

describe('app.js — showWorkflowsModal with mocked fetch', () => {
  beforeEach(() => {
    setupModalDOM();
    ensureOpenFeatureModal();
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('renders workflow list from API response', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/workflows') && url.split('/').length <= 5) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            workflows: [
              { name: 'deploy-check', description: 'Pre-deploy validation' },
              { name: 'security-scan', description: 'Security scanning' },
            ],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showWorkflowsModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('deploy-check');
    expect(body.textContent).toContain('security-scan');
  });

  it('shows empty state when no workflows', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ workflows: [] }),
    });

    await showWorkflowsModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('无');
  });
});

describe('app.js — showCostModal with mocked fetch', () => {
  beforeEach(() => {
    setupModalDOM();
    ensureOpenFeatureModal();
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('renders cost summary from API response', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/cost/summary')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            totalCost: 12.50,
            totalCalls: 150,
            byModel: { 'gpt-4': 8.00 },
            bySkill: { 'CodeReview': 7.00 },
          }),
        });
      }
      if (url.includes('/cost/records')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ records: [] }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showCostModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('12.50');
    expect(body.textContent).toContain('150');
  });

  it('shows error when cost API fails', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('Network error'));

    await showCostModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('失败');
  });
});

describe('app.js — showIssuesModal with mocked fetch', () => {
  beforeEach(() => {
    setupModalDOM();
    ensureOpenFeatureModal();
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('renders recent runs from API response', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/issues/recent-runs')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            runs: [
              { taskId: 'task-1', skillName: 'CodeReview', status: 'COMPLETED' },
              { taskId: 'task-2', skillName: 'BugFinder', status: 'FAILED' },
            ],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showIssuesModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('task-1');
    expect(body.textContent).toContain('CodeReview');
    expect(body.textContent).toContain('BugFinder');
  });

  it('shows empty state when no recent runs', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ runs: [] }),
    });

    await showIssuesModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('无');
  });
});

describe('app.js — showPatrolModal with mocked fetch', () => {
  beforeEach(() => {
    setupModalDOM();
    ensureOpenFeatureModal();
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('renders patrol tasks and reports from API', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/patrol/tasks') && !url.includes('/toggle') && !url.includes('/infer')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            tasks: [
              { taskId: 'patrol-1', name: 'Daily Check', skillName: 'CodeReview', cron: '0 0 8 * * *', active: true },
            ],
          }),
        });
      }
      if (url.includes('/patrol/reports')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            reports: [
              { patrolId: 'patrol-1', triggeredAt: Date.now(), status: 'COMPLETED', anomalyDetected: false, summary: 'OK' },
            ],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showPatrolModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('Daily Check');
    expect(body.textContent).toContain('巡检任务');
  });

  it('shows create form toggle button', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/patrol/tasks') && !url.includes('/toggle') && !url.includes('/infer')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ tasks: [] }),
        });
      }
      if (url.includes('/patrol/reports')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ reports: [] }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showPatrolModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    const createBtn = body.querySelector('#createPatrolBtn');
    expect(createBtn).not.toBeNull();
    expect(createBtn.textContent).toContain('新建');
  });

  it('shows empty state when no patrol tasks', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/patrol/tasks') && !url.includes('/toggle') && !url.includes('/infer')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ tasks: [] }),
        });
      }
      if (url.includes('/patrol/reports')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ reports: [] }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showPatrolModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('无巡检任务');
  });
});

describe('app.js — showAlertsModal with mocked fetch', () => {
  beforeEach(() => {
    setupModalDOM();
    ensureOpenFeatureModal();
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('renders alerts from API response', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/alerts') && !url.includes('/resolve')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            alerts: [
              { id: 'a1', source: 'patrol-1', type: 'patrol', status: 'ACTIVE',
                firstMessage: 'Anomaly detected', firstSeen: Date.now(), lastSeen: Date.now(), count: 3 },
            ],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showAlertsModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('Anomaly detected');
    expect(body.textContent).toContain('告警中');
    expect(body.textContent).toContain('连续告警');
  });

  it('shows empty state when no alerts', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ alerts: [] }),
    });

    await showAlertsModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('无');
  });

  it('resolve button click sends POST request', async () => {
    let resolveCalled = false;
    global.fetch = vi.fn().mockImplementation((url, opts) => {
      if (url.includes('/alerts') && url.includes('/resolve') && opts && opts.method === 'POST') {
        resolveCalled = true;
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ success: true }),
        });
      }
      if (url.includes('/alerts') && !url.includes('/resolve')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            alerts: [
              { id: 'a1', source: 'test', status: 'ACTIVE', firstMessage: 'Alert', count: 1 },
            ],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showAlertsModal();

    const resolveBtn = document.querySelector('[data-alert-id]');
    expect(resolveBtn).not.toBeNull();
    resolveBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise(r => setTimeout(r, 100));
    expect(resolveCalled).toBe(true);
  });
});

describe('app.js — showKnowledgeModal with mocked fetch', () => {
  beforeEach(() => {
    setupModalDOM();
    ensureOpenFeatureModal();
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('renders knowledge status from API', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/knowledge/status')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            fragmentCount: 42,
            maxFragments: 100,
            minScore: 0.7,
            sources: [
              { type: 'directory', dir: '/data/knowledge', writable: true },
            ],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showKnowledgeModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('42');
    expect(body.textContent).toContain('100');
    expect(body.textContent).toContain('0.7');
    expect(body.textContent).toContain('/data/knowledge');
  });

  it('renders search box and search button', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/knowledge/status')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            fragmentCount: 0, maxFragments: 100, minScore: 0,
            sources: [],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showKnowledgeModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.querySelector('#knowledgeSearchInput')).not.toBeNull();
    expect(body.querySelector('#knowledgeSearchBtn')).not.toBeNull();
  });

  it('renders reload and upload buttons', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/knowledge/status')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            fragmentCount: 0, maxFragments: 100, minScore: 0,
            sources: [],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showKnowledgeModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.querySelector('#knowledgeReloadBtn')).not.toBeNull();
    expect(body.querySelector('#knowledgeUploadInput')).not.toBeNull();
  });

  it('shows error when knowledge API fails', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('Network error'));

    await showKnowledgeModal();

    const body = document.querySelector('#featureModal .history-modal-body');
    expect(body.textContent).toContain('失败');
  });

  it('stat fragment click loads fragment list', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/knowledge/status')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            fragmentCount: 2, maxFragments: 100, minScore: 0.5,
            sources: [],
          }),
        });
      }
      if (url.includes('/knowledge/fragments')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            fragments: [
              { title: 'Doc1', source: '/data/1.md', content: 'Content 1' },
              { title: 'Doc2', source: '/data/2.md', content: 'Content 2' },
            ],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showKnowledgeModal();

    const statFragment = document.querySelector('#knowledgeStatFragments');
    expect(statFragment).not.toBeNull();

    // Click to expand
    statFragment.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise(r => setTimeout(r, 100));

    const fragList = document.querySelector('#knowledgeFragmentsList');
    expect(fragList.style.display).not.toBe('none');
    expect(fragList.textContent).toContain('Doc1');
    expect(fragList.textContent).toContain('Doc2');
  });

  it('search button triggers GET /knowledge/search', async () => {
    let searchCalled = false;
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/knowledge/status')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            fragmentCount: 0, maxFragments: 100, minScore: 0,
            sources: [],
          }),
        });
      }
      if (url.includes('/knowledge/search')) {
        searchCalled = true;
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            fragments: [
              { title: 'Result', source: '/data/r.md', content: 'Found', score: 0.92 },
            ],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showKnowledgeModal();

    const searchInput = document.querySelector('#knowledgeSearchInput');
    const searchBtn = document.querySelector('#knowledgeSearchBtn');
    expect(searchInput).not.toBeNull();
    expect(searchBtn).not.toBeNull();

    searchInput.value = 'test query';
    searchBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise(r => setTimeout(r, 100));
    expect(searchCalled).toBe(true);

    const results = document.querySelector('#knowledgeSearchResults');
    expect(results.textContent).toContain('Result');
    expect(results.textContent).toContain('92%');
  });

  it('reload button sends POST and shows status', async () => {
    let reloadCalled = false;
    global.fetch = vi.fn().mockImplementation((url, opts) => {
      if (url.includes('/knowledge/status')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            fragmentCount: 42, maxFragments: 100, minScore: 0.7,
            sources: [],
          }),
        });
      }
      if (url.includes('/knowledge/reload') && opts && opts.method === 'POST') {
        reloadCalled = true;
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ fragmentCount: 50 }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await showKnowledgeModal();

    const reloadBtn = document.querySelector('#knowledgeReloadBtn');
    reloadBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise(r => setTimeout(r, 100));
    expect(reloadCalled).toBe(true);
  });
});

describe('app.js — refreshAlertBadge with mocked fetch', () => {
  beforeEach(() => {
    setupModalDOM();
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('updates badge with total count when alerts exist', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/alerts') && url.includes('page=')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ alerts: [], total: 5 }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await refreshAlertBadge();

    const badge = document.getElementById('alertBadge');
    expect(badge.style.display).not.toBe('none');
    expect(badge.textContent).toBe('5');
  });

  it('hides badge when total is 0', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/alerts')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ alerts: [], total: 0 }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await refreshAlertBadge();

    const badge = document.getElementById('alertBadge');
    expect(badge.style.display).toBe('none');
  });

  it('hides badge on API error', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('Network error'));

    await refreshAlertBadge();

    const badge = document.getElementById('alertBadge');
    expect(badge.style.display).toBe('none');
  });

  it('shows 99+ when total exceeds 99', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/alerts')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ alerts: [], total: 150 }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    await refreshAlertBadge();

    const badge = document.getElementById('alertBadge');
    expect(badge.textContent).toBe('99+');
  });
});
