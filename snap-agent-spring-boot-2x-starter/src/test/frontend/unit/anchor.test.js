import { describe, it, expect, beforeEach, vi } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';

const STATIC_DIR = resolve(__dirname, '../../../main/resources/static/snap-agent');

// Load anchor.js source — it's an IIFE that auto-runs on DOMContentLoaded
const anchorSource = readFileSync(resolve(STATIC_DIR, 'anchor.js'), 'utf-8');

// Mock fetch for the auth/config endpoints that anchor.js calls on init
function mockFetch(ok, data) {
  return vi.fn().mockResolvedValue({
    ok,
    json: () => Promise.resolve(data),
  });
}

function loadAnchorScript() {
  // eslint-disable-next-line no-eval
  eval(anchorSource);
}

describe('anchor.js — DOM scanning', () => {
  let fetchMock;

  beforeEach(() => {
    // Reset DOM
    document.body.innerHTML = '<main></main>';

    // Default: authorized user, enabled anchors
    fetchMock = vi.fn().mockImplementation((url) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ authHeader: 'X-Token', authCookie: 'token' }),
        });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ authorized: true, authenticated: true }),
        });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ enabled: true, disabledPaths: [] }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });
    global.fetch = fetchMock;
    global.localStorage = {
      getItem: vi.fn().mockReturnValue(null),
      setItem: vi.fn(),
    };
  });

  it('injects anchor icon on elements with data-snap-anchor', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="intro"><h2>Introduction</h2><p>Hello</p></section>';

    // Run the IIFE — it will call init() which awaits fetch calls
    loadAnchorScript();
    // Wait for async init to complete
    await new Promise((r) => setTimeout(r, 100));

    const section = document.querySelector('[data-snap-anchor="intro"]');
    expect(section.hasAttribute('data-snap-anchor-injected')).toBe(true);
    const icon = section.querySelector('.snap-anchor-icon');
    expect(icon).not.toBeNull();
    expect(icon.style.cursor).toBe('pointer');
  });

  it('auto-discovers sections without data-snap-anchor when none annotated', async () => {
    document.querySelector('main').innerHTML =
      '<section id="overview"><h2>Overview</h2></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const section = document.getElementById('overview');
    expect(section.hasAttribute('data-snap-anchor-injected')).toBe(true);
    expect(section.getAttribute('data-snap-anchor')).toBe('overview');
  });

  it('auto-discovers h2[id] and h3[id] elements', async () => {
    document.querySelector('main').innerHTML =
      '<h2 id="section1">Section 1</h2><p>Content</p>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const h2 = document.getElementById('section1');
    expect(h2.hasAttribute('data-snap-anchor-injected')).toBe(true);
  });

  it('skips elements with data-snap-mode="inject" during Q&A scan', async () => {
    document.querySelector('main').innerHTML =
      '<div data-snap-anchor="qa-section"><p>Q&A</p></div>' +
      '<div data-snap-mode="inject"><p>Inject</p></div>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const injectEl = document.querySelector('[data-snap-mode="inject"]');
    // Inject elements should NOT get the Q&A anchor icon (they have their own init)
    expect(injectEl.querySelector('.snap-anchor-icon')).toBeNull();
  });

  it('does not inject when anchorConfig.enabled is false', async () => {
    fetchMock.mockImplementation((url) => {
      if (url.includes('/anchor/config')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ enabled: false, disabledPaths: [] }),
        });
      }
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve(
          url.includes('/user-info')
            ? { authorized: true, authenticated: true }
            : { authHeader: '' }
        ),
      });
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="test"><p>Test</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const section = document.querySelector('[data-snap-anchor="test"]');
    expect(section.hasAttribute('data-snap-anchor-injected')).toBe(false);
  });

  it('does not inject when user is not authorized', async () => {
    fetchMock.mockImplementation((url) => {
      if (url.includes('/user-info')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ authorized: false, authenticated: true }),
        });
      }
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve(
          url.includes('/anchor/config')
            ? { enabled: true, disabledPaths: [] }
            : { authHeader: '' }
        ),
      });
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="test"><p>Test</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const section = document.querySelector('[data-snap-anchor="test"]');
    expect(section.hasAttribute('data-snap-anchor-injected')).toBe(false);
  });
});

describe('anchor.js — path matching (isPathDisabled)', () => {
  beforeEach(() => {
    document.body.innerHTML = '<main></main>';
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            enabled: true,
            disabledPaths: ['/admin/**', '/private'],
          }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });
  });

  it('skips scanning when path matches disabled pattern with /**', async () => {
    // Set window.location to a disabled path
    Object.defineProperty(window, 'location', {
      value: { pathname: '/admin/dashboard', href: '' },
      writable: true,
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="test"><p>Test</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const section = document.querySelector('[data-snap-anchor="test"]');
    expect(section.hasAttribute('data-snap-anchor-injected')).toBe(false);
  });

  it('skips scanning when path exactly matches disabled pattern', async () => {
    Object.defineProperty(window, 'location', {
      value: { pathname: '/private', href: '' },
      writable: true,
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="test"><p>Test</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const section = document.querySelector('[data-snap-anchor="test"]');
    expect(section.hasAttribute('data-snap-anchor-injected')).toBe(false);
  });

  it('scans when path does not match any disabled pattern', async () => {
    Object.defineProperty(window, 'location', {
      value: { pathname: '/public/page', href: '' },
      writable: true,
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="test"><p>Test</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const section = document.querySelector('[data-snap-anchor="test"]');
    expect(section.hasAttribute('data-snap-anchor-injected')).toBe(true);
  });
});

describe('anchor.js — drawer creation', () => {
  beforeEach(() => {
    document.body.innerHTML = '<main></main>';
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });
    global.localStorage = { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn() };
  });

  it('opens drawer when anchor icon is clicked', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="qa"><h2>Q&A Section</h2><p>Content here</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    expect(icon).not.toBeNull();

    // Click the icon to open the drawer
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));

    // Drawer host should be created
    const drawerHost = document.getElementById('snap-anchor-drawer-host');
    expect(drawerHost).not.toBeNull();
    expect(drawerHost.style.zIndex).toBe('99999');
  });

  it('drawer contains title with anchor name', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="my-section"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    const drawerHost = document.getElementById('snap-anchor-drawer-host');
    const shadow = drawerHost.shadowRoot;
    const title = shadow.getElementById('snap-title');
    expect(title.textContent).toBe('my-section');
  });

  it('drawer has close button that closes drawer', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="close-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    const drawerHost = document.getElementById('snap-anchor-drawer-host');
    expect(drawerHost.style.transform).toContain('translateX(0)');

    const shadow = drawerHost.shadowRoot;
    const closeBtn = shadow.getElementById('snap-close');
    closeBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(drawerHost.style.transform).toContain('translateX(100%)');
  });

  it('drawer has send button and input textarea', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="send-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    const shadow = document.getElementById('snap-anchor-drawer-host').shadowRoot;
    expect(shadow.getElementById('snap-send')).not.toBeNull();
    expect(shadow.getElementById('snap-input')).not.toBeNull();
  });
});

describe('anchor.js — inject mode', () => {
  beforeEach(() => {
    document.body.innerHTML = '<main></main>';
    global.fetch = vi.fn().mockImplementation((url, opts) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      if (url.includes('/anchor/inject') && opts && opts.method === 'POST') {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ html: '<div class="injected">Generated content</div>' }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });
    global.localStorage = { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn() };
  });

  it('replaces loading placeholder with injected HTML on success', async () => {
    document.querySelector('main').innerHTML =
      '<div data-snap-anchor="inject-test" data-snap-mode="inject"></div>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 200));

    const injected = document.querySelector('.injected');
    expect(injected).not.toBeNull();
    expect(injected.textContent).toContain('Generated content');
  });

  it('shows fallback content when inject fails', async () => {
    global.fetch = vi.fn().mockImplementation((url, opts) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      if (url.includes('/anchor/inject')) {
        return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    document.querySelector('main').innerHTML =
      '<div data-snap-anchor="fail-test" data-snap-mode="inject" data-snap-fallback="Fallback content"></div>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 200));

    expect(document.body.textContent).toContain('Fallback content');
  });

  it('removes loading placeholder when inject fails without fallback', async () => {
    global.fetch = vi.fn().mockImplementation((url, opts) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      if (url.includes('/anchor/inject')) {
        return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    document.querySelector('main').innerHTML =
      '<div data-snap-anchor="no-fallback" data-snap-mode="inject"></div>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 200));

    const loading = document.querySelector('.snap-inject-loading');
    expect(loading).toBeNull();
  });
});

describe('anchor.js — auth helpers', () => {
  beforeEach(() => {
    document.body.innerHTML = '<main></main>';
  });

  it('loads auth config from /auth-config endpoint', async () => {
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ authHeader: 'Authorization', authCookie: 'session' }),
        });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="auth-test"><p>Test</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    // Verify auth-config was fetched
    expect(global.fetch).toHaveBeenCalledWith(expect.stringContaining('/auth-config'));
    // Verify user-info was fetched
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/user-info'),
      expect.objectContaining({ headers: expect.any(Object) })
    );
  });
});

describe('anchor.js — drawer send flow (sendMessage + startPreprocess + streamSSE)', () => {
  let fetchMock;
  let eventSourceMock;

  beforeEach(() => {
    document.body.innerHTML = '<main></main>';

    fetchMock = vi.fn().mockImplementation((url, opts) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      if (url.includes('/skills')) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            skills: [{ name: 'CodeReview', description: 'Review code', availability: 'AVAILABLE', source: 'custom' }],
          }),
        });
      }
      if (url.includes('/anchor/preprocess') && opts && opts.method === 'POST') {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ preprocessId: 'pre-123' }),
        });
      }
      if (url.includes('/runs') && opts && opts.method === 'POST') {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ taskId: 'task-anchor-1' }),
        });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });
    global.fetch = fetchMock;
    global.localStorage = { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn() };

    // Mock EventSource (jsdom doesn't have it)
    eventSourceMock = {
      addEventListener: vi.fn(),
      close: vi.fn(),
      readyState: 0,
      url: '',
    };
    global.EventSource = vi.fn().mockImplementation((url) => {
      eventSourceMock.url = url;
      return eventSourceMock;
    });
  });

  afterEach(() => {
    delete global.EventSource;
  });

  it('fetches skill info when drawer opens', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="test-section"><p>Content here</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 100));

    // /skills should have been fetched
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/skills'), expect.any(Object));
  });

  it('startPreprocess sends POST to /anchor/preprocess', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="preprocess-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    // Check that /anchor/preprocess was called
    const preprocessCalls = fetchMock.mock.calls.filter(c => c[0].includes('/anchor/preprocess'));
    expect(preprocessCalls.length).toBeGreaterThan(0);
    expect(preprocessCalls[0][1].method).toBe('POST');
  });

  it('sendMessage sends POST to /runs with skillId and inputs', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="send-flow-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    // Open drawer
    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const drawerHost = document.getElementById('snap-anchor-drawer-host');
    expect(drawerHost).not.toBeNull();
    const shadow = drawerHost.shadowRoot;

    // Type a question
    const input = shadow.getElementById('snap-input');
    expect(input).not.toBeNull();
    input.value = 'What is this section about?';

    // Click send
    const sendBtn = shadow.getElementById('snap-send');
    expect(sendBtn).not.toBeNull();
    sendBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise((r) => setTimeout(r, 200));

    // /runs should have been called with POST
    const runsCalls = fetchMock.mock.calls.filter(c => c[0].includes('/runs') && c[1] && c[1].method === 'POST');
    expect(runsCalls.length).toBeGreaterThan(0);

    // Verify body contains skillId and inputs
    const body = JSON.parse(runsCalls[0][1].body);
    expect(body.skillId).toBeDefined();
    expect(body.inputs).toBeDefined();
    expect(body.inputs.message).toBe('What is this section about?');
  });

  it('sendMessage shows user message in drawer content', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="msg-display-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const shadow = document.getElementById('snap-anchor-drawer-host').shadowRoot;
    const input = shadow.getElementById('snap-input');
    input.value = 'Test question';

    const sendBtn = shadow.getElementById('snap-send');
    sendBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise((r) => setTimeout(r, 100));

    // Check user message appeared
    const userMsg = shadow.querySelector('.msg-user');
    expect(userMsg).not.toBeNull();
    expect(userMsg.textContent).toBe('Test question');
  });

  it('sendMessage creates EventSource for SSE streaming', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="sse-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const shadow = document.getElementById('snap-anchor-drawer-host').shadowRoot;
    const input = shadow.getElementById('snap-input');
    input.value = 'Stream test';

    shadow.getElementById('snap-send').dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise((r) => setTimeout(r, 200));

    // EventSource should have been created
    expect(global.EventSource).toHaveBeenCalled();
    expect(eventSourceMock.url).toContain('/runs/');
    expect(eventSourceMock.url).toContain('/stream');
  });

  it('sendMessage handles 401 with login required message', async () => {
    fetchMock.mockImplementation((url, opts) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      if (url.includes('/skills')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ skills: [] }) });
      }
      if (url.includes('/anchor/preprocess')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
      }
      if (url.includes('/runs') && opts && opts.method === 'POST') {
        return Promise.resolve({ ok: false, status: 401, json: () => Promise.resolve({}) });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="auth-fail-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const shadow = document.getElementById('snap-anchor-drawer-host').shadowRoot;
    const input = shadow.getElementById('snap-input');
    input.value = 'Auth test';

    shadow.getElementById('snap-send').dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise((r) => setTimeout(r, 200));

    // Should show login required message
    const errMsg = shadow.querySelector('.msg-err');
    expect(errMsg).not.toBeNull();
    expect(errMsg.textContent).toContain('Login');
  });

  it('sendMessage handles 403 with permission denied message', async () => {
    fetchMock.mockImplementation((url, opts) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      if (url.includes('/skills')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ skills: [] }) });
      }
      if (url.includes('/anchor/preprocess')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
      }
      if (url.includes('/runs') && opts && opts.method === 'POST') {
        return Promise.resolve({ ok: false, status: 403, json: () => Promise.resolve({}) });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="forbidden-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const shadow = document.getElementById('snap-anchor-drawer-host').shadowRoot;
    const input = shadow.getElementById('snap-input');
    input.value = 'Forbidden test';

    shadow.getElementById('snap-send').dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise((r) => setTimeout(r, 200));

    const errMsg = shadow.querySelector('.msg-err');
    expect(errMsg).not.toBeNull();
    expect(errMsg.textContent).toContain('Permission');
  });

  it('sendMessage handles 429 rate limit message', async () => {
    fetchMock.mockImplementation((url, opts) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      if (url.includes('/skills')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ skills: [] }) });
      }
      if (url.includes('/anchor/preprocess')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
      }
      if (url.includes('/runs') && opts && opts.method === 'POST') {
        return Promise.resolve({ ok: false, status: 429, json: () => Promise.resolve({}) });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="rate-limit-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const shadow = document.getElementById('snap-anchor-drawer-host').shadowRoot;
    const input = shadow.getElementById('snap-input');
    input.value = 'Rate limit test';

    shadow.getElementById('snap-send').dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise((r) => setTimeout(r, 200));

    const errMsg = shadow.querySelector('.msg-err');
    expect(errMsg).not.toBeNull();
    expect(errMsg.textContent).toContain('Rate limited');
  });

  it('sendMessage handles network error gracefully', async () => {
    fetchMock.mockImplementation((url, opts) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      if (url.includes('/skills')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ skills: [] }) });
      }
      if (url.includes('/anchor/preprocess')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
      }
      if (url.includes('/runs') && opts && opts.method === 'POST') {
        return Promise.reject(new Error('Network failure'));
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });

    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="network-error-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const shadow = document.getElementById('snap-anchor-drawer-host').shadowRoot;
    const input = shadow.getElementById('snap-input');
    input.value = 'Network test';

    shadow.getElementById('snap-send').dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise((r) => setTimeout(r, 200));

    const errMsg = shadow.querySelector('.msg-err');
    expect(errMsg).not.toBeNull();
    expect(errMsg.textContent).toContain('Network error');
  });

  it('sendMessage does nothing when input is empty', async () => {
    document.querySelector('main').innerHTML =
      '<section data-snap-anchor="empty-input-test"><p>Content</p></section>';

    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    const icon = document.querySelector('.snap-anchor-icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const shadow = document.getElementById('snap-anchor-drawer-host').shadowRoot;
    const input = shadow.getElementById('snap-input');
    input.value = '';

    const initialFetchCalls = fetchMock.mock.calls.length;
    shadow.getElementById('snap-send').dispatchEvent(new MouseEvent('click', { bubbles: true }));

    await new Promise((r) => setTimeout(r, 100));

    // No new /runs POST should have been made
    const newRunsCalls = fetchMock.mock.calls.slice(initialFetchCalls).filter(c => c[0].includes('/runs'));
    expect(newRunsCalls.length).toBe(0);
  });
});

describe('anchor.js — MutationObserver for SPA', () => {
  beforeEach(() => {
    document.body.innerHTML = '<main></main>';
    global.fetch = vi.fn().mockImplementation((url) => {
      if (url.includes('/auth-config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authHeader: '' }) });
      }
      if (url.includes('/user-info')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ authorized: true, authenticated: true }) });
      }
      if (url.includes('/anchor/config')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ enabled: true, disabledPaths: [] }) });
      }
      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) });
    });
    global.localStorage = { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn() };
  });

  it('discovers new anchors added after initial scan', async () => {
    loadAnchorScript();
    await new Promise((r) => setTimeout(r, 100));

    // Add a new section after initial scan
    const main = document.querySelector('main');
    const newSection = document.createElement('section');
    newSection.setAttribute('data-snap-anchor', 'dynamic');
    newSection.innerHTML = '<p>New content</p>';
    main.appendChild(newSection);

    // Wait for MutationObserver debounce (800ms) + scan
    await new Promise((r) => setTimeout(r, 1200));

    expect(newSection.hasAttribute('data-snap-anchor-injected')).toBe(true);
  });
});
