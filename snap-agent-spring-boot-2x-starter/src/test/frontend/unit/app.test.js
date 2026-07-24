import { describe, it, expect, beforeEach, vi } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';

const STATIC_DIR = resolve(__dirname, '../../../main/resources/static/snap-agent');
const appSource = readFileSync(resolve(STATIC_DIR, 'app.js'), 'utf-8');

// Load app.js — it declares globals like formatTime, escapeHtml, etc.
// We eval only the first ~500 lines to get utility functions without
// the full DOM initialization at the bottom.
const utilitySource = appSource.split('// ===== Init =====')[0];
// eslint-disable-next-line no-eval
eval(utilitySource);

describe('app.js — formatTime', () => {
  it('formats a timestamp to HH:MM:SS', () => {
    const ts = new Date('2024-01-15T10:30:45').getTime();
    expect(formatTime(ts)).toBe('10:30:45');
  });

  it('pads single-digit hours, minutes, seconds', () => {
    const ts = new Date('2024-01-15T01:05:09').getTime();
    expect(formatTime(ts)).toBe('01:05:09');
  });

  it('returns empty string for null/undefined', () => {
    expect(formatTime(null)).toBe('');
    expect(formatTime(undefined)).toBe('');
    expect(formatTime(0)).toBe('00:00:00'); // epoch = 00:00:00 UTC, but local time varies
  });
});

describe('app.js — escapeHtml', () => {
  it('escapes HTML special characters', () => {
    expect(escapeHtml('<script>')).toBe('&lt;script&gt;');
  });

  it('escapes ampersand', () => {
    expect(escapeHtml('a & b')).toBe('a &amp; b');
  });

  it('returns empty string for null/undefined', () => {
    expect(escapeHtml(null)).toBe('');
    expect(escapeHtml(undefined)).toBe('');
  });

  it('escapes quotes via DOM textContent', () => {
    // This escapeHtml uses div.textContent → innerHTML, so it relies on browser escaping
    expect(escapeHtml('"quoted"')).toContain('&quot;');
  });
});

describe('app.js — getSkillState', () => {
  beforeEach(() => {
    // Reset global state
    skillChatState = {};
  });

  it('creates state for new skill name', () => {
    const state = getSkillState('testSkill');
    expect(state).toBeDefined();
    expect(state.conversationId).toBeNull();
    expect(state.transcript).toEqual([]);
    expect(state.conversationMessages).toEqual([]);
    expect(state.stream).toBeNull();
    expect(state.taskIssueStates).toEqual({});
  });

  it('returns existing state on second call', () => {
    const state1 = getSkillState('persistSkill');
    state1.conversationId = 'conv-123';
    const state2 = getSkillState('persistSkill');
    expect(state2.conversationId).toBe('conv-123');
    expect(state2).toBe(state1);
  });

  it('maintains separate state per skill name', () => {
    const stateA = getSkillState('skillA');
    const stateB = getSkillState('skillB');
    stateA.conversationId = 'conv-a';
    stateB.conversationId = 'conv-b';
    expect(getSkillState('skillA').conversationId).toBe('conv-a');
    expect(getSkillState('skillB').conversationId).toBe('conv-b');
  });
});

describe('app.js — getTaskIssueState', () => {
  beforeEach(() => {
    skillChatState = {};
  });

  it('returns null when taskId is null or undefined', () => {
    expect(getTaskIssueState('skill', null)).toBeNull();
    expect(getTaskIssueState('skill', undefined)).toBeNull();
  });

  it('creates issue state for new taskId', () => {
    const state = getTaskIssueState('skill', 'task-1');
    expect(state).toBeDefined();
    expect(state.solutionProposed).toBe(false);
    expect(state.issueId).toBeNull();
    expect(state.issueStatus).toBeNull();
  });

  it('returns existing issue state on second call', () => {
    const state1 = getTaskIssueState('skill', 'task-1');
    state1.solutionProposed = true;
    state1.issueId = 'ISSUE-42';
    const state2 = getTaskIssueState('skill', 'task-1');
    expect(state2.solutionProposed).toBe(true);
    expect(state2.issueId).toBe('ISSUE-42');
  });

  it('maintains separate issue states per skill+task', () => {
    getTaskIssueState('skillA', 'task-1').solutionProposed = true;
    expect(getTaskIssueState('skillB', 'task-1').solutionProposed).toBe(false);
    expect(getTaskIssueState('skillA', 'task-2').solutionProposed).toBe(false);
  });
});

describe('app.js — profileLabel', () => {
  beforeEach(() => {
    currentProfiles = [];
  });

  it('returns empty string when no profiles', () => {
    expect(profileLabel()).toBe('');
  });

  it('returns single profile', () => {
    currentProfiles = ['prod'];
    expect(profileLabel()).toBe('prod');
  });

  it('returns comma-separated profiles', () => {
    currentProfiles = ['prod', 'staging', 'test'];
    expect(profileLabel()).toBe('prod,staging,test');
  });
});

describe('app.js — toast', () => {
  it('creates a toast element with message', () => {
    toast('Hello toast');
    const el = document.querySelector('.toast');
    expect(el).not.toBeNull();
    expect(el.textContent).toBe('Hello toast');
  });

  it('applies success type class by default', () => {
    toast('message');
    const el = document.querySelector('.toast');
    expect(el.classList.contains('success')).toBe(true);
  });

  it('applies error type class', () => {
    toast('error message', 'error');
    const el = document.querySelector('.toast');
    expect(el.classList.contains('error')).toBe(true);
  });

  it('removes toast after 3 seconds', async () => {
    toast('temp');
    expect(document.querySelector('.toast')).not.toBeNull();
    // Fast-forward timers
    vi.useFakeTimers();
    vi.advanceTimersByTime(3100);
    expect(document.querySelector('.toast')).toBeNull();
    vi.useRealTimers();
  });
});

describe('app.js — showAuthPrompt', () => {
  it('creates auth prompt overlay with title and message', () => {
    showAuthPrompt('未登录', '请先登录');
    const overlay = document.getElementById('authPrompt');
    expect(overlay).not.toBeNull();
    expect(overlay.querySelector('.auth-title').textContent).toBe('未登录');
    expect(overlay.querySelector('.auth-msg').textContent).toBe('请先登录');
  });

  it('does not create duplicate auth prompt', () => {
    showAuthPrompt('First', 'msg1');
    showAuthPrompt('Second', 'msg2');
    const overlays = document.querySelectorAll('#authPrompt');
    expect(overlays.length).toBe(1);
    expect(overlays[0].querySelector('.auth-title').textContent).toBe('First');
  });
});

describe('app.js — handleAuthError', () => {
  it('shows auth prompt for 401 and returns true', () => {
    const result = handleAuthError({ status: 401 });
    expect(result).toBe(true);
    expect(document.getElementById('authPrompt')).not.toBeNull();
  });

  it('shows auth prompt for 403 and returns true', () => {
    const result = handleAuthError({ status: 403 });
    expect(result).toBe(true);
    expect(document.getElementById('authPrompt')).not.toBeNull();
  });

  it('returns false for 200 OK', () => {
    document.body.innerHTML = '';
    const result = handleAuthError({ status: 200 });
    expect(result).toBe(false);
    expect(document.getElementById('authPrompt')).toBeNull();
  });

  it('returns false for 500 server error', () => {
    const result = handleAuthError({ status: 500 });
    expect(result).toBe(false);
  });
});

describe('app.js — toggleSection', () => {
  it('collapses an expanded section', () => {
    document.body.innerHTML =
      '<div id="header" data-collapsed="false"><span class="section-toggle">▾</span></div>' +
      '<ul id="list" style="display:block"></ul>';

    toggleSection('header', 'list');

    const header = document.getElementById('header');
    const list = document.getElementById('list');
    expect(header.dataset.collapsed).toBe('true');
    expect(header.classList.contains('collapsed')).toBe(true);
    expect(header.querySelector('.section-toggle').textContent).toBe('▸');
    expect(list.style.display).toBe('none');
  });

  it('expands a collapsed section', () => {
    document.body.innerHTML =
      '<div id="header" class="collapsed" data-collapsed="true"><span class="section-toggle">▸</span></div>' +
      '<ul id="list" style="display:none"></ul>';

    toggleSection('header', 'list');

    const header = document.getElementById('header');
    const list = document.getElementById('list');
    expect(header.dataset.collapsed).toBe('false');
    expect(header.classList.contains('collapsed')).toBe(false);
    expect(header.querySelector('.section-toggle').textContent).toBe('▾');
    expect(list.style.display).toBe('block');
  });
});

describe('app.js — authHeaders', () => {
  beforeEach(() => {
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('returns empty headers when no auth config', () => {
    const headers = authHeaders();
    expect(Object.keys(headers).length).toBe(0);
  });

  it('adds auth header from localStorage when configured', () => {
    authConfig = { authHeader: 'Authorization', authCookie: '', authLocalStorageKey: 'token' };
    global.localStorage = { getItem: vi.fn().mockReturnValue('abc123') };
    const headers = authHeaders();
    expect(headers['Authorization']).toBe('abc123');
  });

  it('adds auth header from cookie when configured', () => {
    authConfig = { authHeader: 'X-Token', authCookie: 'session', authLocalStorageKey: '' };
    Object.defineProperty(document, 'cookie', {
      value: 'session=cookieValue',
      writable: true,
    });
    const headers = authHeaders();
    expect(headers['X-Token']).toBe('cookieValue');
  });

  it('does not overwrite existing auth header', () => {
    authConfig = { authHeader: 'Authorization', authCookie: '', authLocalStorageKey: 'token' };
    global.localStorage = { getItem: vi.fn().mockReturnValue('token123') };
    const headers = authHeaders({ Authorization: 'existing' });
    expect(headers['Authorization']).toBe('existing');
  });
});

describe('app.js — loadSkills with mocked fetch', () => {
  beforeEach(() => {
    // Set up minimal DOM for loadSkills
    document.body.innerHTML = `
      <ul id="hostSkills"></ul>
      <ul id="builtinSkills"></ul>
      <ul id="disabledSkills"></ul>
      <ul id="skillIcons"></ul>
      <span id="skillCount"></span>
      <span id="hostCount"></span>
      <span id="builtinCount"></span>
      <div id="hostSectionHeader" data-collapsed="false"><span class="section-toggle">▾</span></div>
      <div id="builtinSectionHeader" data-collapsed="true"><span class="section-toggle">▸</span></div>
      <button id="toggleDisabledBtn"></button>
    `;
    skillChatState = {};
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('renders host and builtin skills', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        skills: [
          { name: 'MySkill', description: 'Custom skill', availability: 'AVAILABLE', source: 'custom' },
          { name: 'Builtin', description: 'Built-in', availability: 'AVAILABLE', source: 'builtin' },
        ],
      }),
    });

    await loadSkills();

    expect(document.getElementById('skillCount').textContent).toBe('2');
    expect(document.getElementById('hostCount').textContent).toBe('1');
    expect(document.getElementById('builtinCount').textContent).toBe('1');
    expect(document.querySelector('#hostSkills li').dataset.skillId).toBe('MySkill');
    expect(document.querySelector('#builtinSkills li').dataset.skillId).toBe('Builtin');
  });

  it('renders unavailable skills in disabled list', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        skills: [
          { name: 'Available', description: 'OK', availability: 'AVAILABLE', source: 'custom' },
          { name: 'Disabled', description: 'Nope', availability: 'UNAVAILABLE', source: 'custom', unavailableReason: 'missing dep' },
        ],
      }),
    });

    await loadSkills();

    expect(document.getElementById('skillCount').textContent).toBe('1');
    const disabledItem = document.querySelector('#disabledSkills li');
    expect(disabledItem).not.toBeNull();
    expect(disabledItem.dataset.skillId).toBe('Disabled');
    expect(disabledItem.style.opacity).toBe('0.5');
  });

  it('creates letter icons for collapsed sidebar', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        skills: [
          { name: 'Alpha', description: 'A', availability: 'AVAILABLE', source: 'custom' },
          { name: 'Beta', description: 'B', availability: 'AVAILABLE', source: 'custom' },
        ],
      }),
    });

    await loadSkills();

    const icons = document.querySelectorAll('#skillIcons li');
    expect(icons.length).toBe(2);
    expect(icons[0].textContent).toBe('A');
    expect(icons[1].textContent).toBe('B');
  });

  it('does not call showAuthPrompt on successful response', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ skills: [] }),
    });

    await loadSkills();

    expect(document.getElementById('authPrompt')).toBeNull();
  });
});

describe('app.js — loadModels with mocked fetch', () => {
  beforeEach(() => {
    document.body.innerHTML = '<select id="modelSelect"></select>';
    global.localStorage = { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn() };
    authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
  });

  it('populates model select with options', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        allowed: ['gpt-4', 'claude-3', 'gemini'],
        default: 'claude-3',
      }),
    });

    await loadModels();

    const select = document.getElementById('modelSelect');
    expect(select.children.length).toBe(3);
    expect(select.children[0].value).toBe('gpt-4');
    expect(select.children[1].value).toBe('claude-3');
    expect(select.children[1].selected).toBe(true);
    expect(select.children[2].value).toBe('gemini');
  });

  it('restores cached model from localStorage', async () => {
    global.localStorage = {
      getItem: vi.fn().mockReturnValue('gemini'),
      setItem: vi.fn(),
    };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        allowed: ['gpt-4', 'claude-3', 'gemini'],
        default: 'claude-3',
      }),
    });

    await loadModels();

    const select = document.getElementById('modelSelect');
    expect(select.value).toBe('gemini');
  });

  it('persists model selection on change', async () => {
    const setItemMock = vi.fn();
    global.localStorage = { getItem: vi.fn().mockReturnValue(null), setItem: setItemMock };

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        allowed: ['gpt-4', 'claude-3'],
        default: 'gpt-4',
      }),
    });

    await loadModels();

    const select = document.getElementById('modelSelect');
    select.value = 'claude-3';
    select.dispatchEvent(new Event('change'));

    expect(setItemMock).toHaveBeenCalledWith('snap-agent.model', 'claude-3');
  });
});
