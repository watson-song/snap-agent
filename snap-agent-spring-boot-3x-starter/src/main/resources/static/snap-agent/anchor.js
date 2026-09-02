/**
 * SnapAgent Anchor Q&A — v0.4
 *
 * Page-section anchor script: scans DOM for [data-snap-anchor] regions,
 * injects anchor icons, and opens a right-side drawer for LLM Q&A
 * about that section's content.
 *
 * Self-contained — no external dependencies. Uses Shadow DOM for style
 * isolation. Inherits auth from SnapAgent's auth-config endpoint.
 */
(function () {
    'use strict';

    var BASE = '/snap-agent';
    var anchorConfig = { enabled: true, disabledPaths: [] };
    var authConfig = {};
    var userAuthorized = false;
    var scanTimer = null;
    var observer = null;

    // ---- Auth helpers (reuse SnapAgent's auth-config) ----

    async function loadAuthConfig() {
        try {
            var resp = await fetch(BASE + '/auth-config');
            if (resp.ok) authConfig = await resp.json();
        } catch (e) { /* ignore */ }
    }

    function getAuthToken() {
        if (authConfig.authLocalStorageKey) {
            try {
                var val = localStorage.getItem(authConfig.authLocalStorageKey);
                if (val) return val;
            } catch (e) { /* ignore */ }
        }
        if (authConfig.authCookie) {
            var match = document.cookie.match(new RegExp('(^|;\\s*)' + authConfig.authCookie + '=([^;]+)'));
            if (match) return match[2];
        }
        return null;
    }

    function authHeaders(headers) {
        headers = headers || {};
        if (authConfig.authHeader) {
            var token = getAuthToken();
            if (token) headers[authConfig.authHeader] = token;
        }
        return headers;
    }

    async function checkUserStatus() {
        try {
            var resp = await fetch(BASE + '/user-info', { headers: authHeaders() });
            if (!resp.ok) return false;
            var data = await resp.json();
            return data.authorized === true;
        } catch (e) { return false; }
    }

    async function loadAnchorConfig() {
        try {
            var resp = await fetch(BASE + '/anchor/config');
            if (resp.ok) anchorConfig = await resp.json();
        } catch (e) { /* ignore */ }
    }

    // ---- Path matching ----

    function isPathDisabled(path) {
        var paths = anchorConfig.disabledPaths || [];
        for (var i = 0; i < paths.length; i++) {
            var pattern = paths[i];
            if (pattern.endsWith('/**')) {
                var prefix = pattern.slice(0, -3);
                if (path === prefix || path.startsWith(prefix + '/')) return true;
            } else {
                if (path === pattern || path.startsWith(pattern + '/')) return true;
            }
        }
        return false;
    }

    // ---- DOM scanning ----

    function scanAnchors() {
        if (!anchorConfig.enabled) return;
        if (isPathDisabled(window.location.pathname)) return;

        var main = document.querySelector('main') || document.body;
        // Q&A anchors: skip inject-mode elements (they have their own init path)
        var anchors = main.querySelectorAll(
            '[data-snap-anchor]:not([data-snap-anchor-injected]):not([data-snap-mode="inject"])'
        );
        anchors.forEach(function (el) {
            injectAnchorIcon(el);
            el.setAttribute('data-snap-anchor-injected', 'true');
        });

        // Fallback: auto-discover sections without annotations
        if (anchors.length === 0) {
            var sections = main.querySelectorAll('section, h2[id], h3[id]');
            sections.forEach(function (el) {
                if (!el.hasAttribute('data-snap-anchor-injected') && !el.hasAttribute('data-snap-anchor') && !el.hasAttribute('data-snap-mode')) {
                    var name = el.getAttribute('data-snap-anchor') || el.id || el.textContent.trim().slice(0, 50);
                    el.setAttribute('data-snap-anchor', name);
                    injectAnchorIcon(el);
                    el.setAttribute('data-snap-anchor-injected', 'true');
                }
            });
        }

        // Inject anchors (use separate marker so Q&A pass doesn't block them)
        var injectAnchors = main.querySelectorAll(
            '[data-snap-mode="inject"]:not([data-snap-inject-init])'
        );
        injectAnchors.forEach(function (el) {
            el.setAttribute('data-snap-inject-init', 'true');
            initInjectAnchor(el);
        });
    }

    function injectAnchorIcon(el) {
        var name = el.getAttribute('data-snap-anchor');
        var skill = el.getAttribute('data-snap-skill') || '';
        var icon = document.createElement('div');
        icon.className = 'snap-anchor-icon';
        icon.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>';
        icon.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:50%;background:#6366f1;color:#fff;cursor:pointer;float:right;margin-left:8px;opacity:0.6;transition:opacity 0.2s;z-index:9998;';
        icon.addEventListener('mouseenter', function () { icon.style.opacity = '1'; });
        icon.addEventListener('mouseleave', function () { icon.style.opacity = '0.6'; });
        icon.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            openDrawer(el, name, skill);
        });
        // Insert at the top of the element
        el.insertBefore(icon, el.firstChild);
    }

    // ---- HTML → Markdown (simple converter) ----

    function htmlToMarkdown(html) {
        var tmp = document.createElement('div');
        tmp.innerHTML = html;
        return convertNode(tmp);
    }

    function convertNode(node) {
        var result = '';
        node.childNodes.forEach(function (child) {
            if (child.nodeType === Node.TEXT_NODE) {
                result += child.textContent;
            } else if (child.nodeType === Node.ELEMENT_NODE) {
                result += convertElement(child);
            }
        });
        return result.trim();
    }

    function convertElement(el) {
        var tag = el.tagName.toLowerCase();
        var inner = convertNode(el);
        switch (tag) {
            case 'h1': return '\n# ' + inner + '\n';
            case 'h2': return '\n## ' + inner + '\n';
            case 'h3': return '\n### ' + inner + '\n';
            case 'h4': return '\n#### ' + inner + '\n';
            case 'p': return inner + '\n\n';
            case 'strong': case 'b': return '**' + inner + '**';
            case 'em': case 'i': return '*' + inner + '*';
            case 'code': return '`' + inner + '`';
            case 'pre': return '\n```\n' + inner + '\n```\n';
            case 'a': return '[' + inner + '](' + (el.href || '') + ')';
            case 'li': return '- ' + inner + '\n';
            case 'ul': case 'ol': return inner + '\n';
            case 'br': return '\n';
            case 'table': return convertTable(el);
            default: return inner;
        }
    }

    function convertTable(table) {
        var rows = table.querySelectorAll('tr');
        var result = '\n';
        rows.forEach(function (row, idx) {
            var cells = row.querySelectorAll('th, td');
            var line = '| ' + Array.from(cells).map(function (c) { return convertNode(c).trim(); }).join(' | ') + ' |';
            result += line + '\n';
            if (idx === 0) {
                result += '|' + Array.from(cells).map(function () { return '---'; }).join('|') + '|\n';
            }
        });
        return result + '\n';
    }

    // ---- Shadow DOM Drawer ----

    var drawer = null;
    var drawerHost = null;

    function createDrawer() {
        if (drawerHost) return;
        drawerHost = document.createElement('div');
        drawerHost.id = 'snap-anchor-drawer-host';
        drawerHost.style.cssText = 'position:fixed;top:0;right:0;width:400px;height:100vh;z-index:99999;transform:translateX(100%);transition:transform 0.35s cubic-bezier(0.22,1,0.36,1);';
        document.body.appendChild(drawerHost);
        var shadow = drawerHost.attachShadow({ mode: 'open' });
        shadow.innerHTML = getDrawerTemplate();
        drawer = shadow;

        // Close button
        shadow.getElementById('snap-close').addEventListener('click', closeDrawer);
        // Handle click also closes
        shadow.getElementById('snap-handle').addEventListener('click', closeDrawer);
        // Send button
        shadow.getElementById('snap-send').addEventListener('click', sendMessage);
        // Enter key in input (skip IME composition Enter)
        shadow.getElementById('snap-input').addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
                e.preventDefault();
                sendMessage();
            }
        });
    }

    function getDrawerTemplate() {
        return '<style>' +
            ':host{all:initial}' +
            '.drawer{position:relative;width:100%;height:100%;background:#fff;display:flex;flex-direction:column;font-family:system-ui,-apple-system,sans-serif;font-size:14px;color:#1a1a1a;box-shadow:-4px 0 24px rgba(0,0,0,0.12);border-radius:16px 0 0 16px;overflow:hidden}' +
            '.drawer-handle{position:absolute;left:-8px;top:50%;transform:translateY(-50%);width:6px;height:56px;background:linear-gradient(180deg,#6366f1,#4f46e5);border-radius:3px 0 0 3px;cursor:pointer;box-shadow:-2px 0 6px rgba(99,102,241,0.3);display:flex;align-items:center;justify-content:center}' +
            '.drawer-handle::after{content:"";width:2px;height:24px;background:rgba(255,255,255,0.5);border-radius:1px}' +
            '.header{display:flex;align-items:center;justify-content:space-between;padding:12px 16px;border-bottom:1px solid #e5e7eb;background:#6366f1;color:#fff;border-radius:16px 0 0 0}' +
            '.header-left{display:flex;flex-direction:column;min-width:0;flex:1}' +
            '.header h3{margin:0;font-size:15px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
            '.header .subtitle{font-size:11px;opacity:0.85;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
            '.close{cursor:pointer;font-size:20px;line-height:1;opacity:0.8;flex-shrink:0;padding:0 4px}' +
            '.close:hover{opacity:1}' +
            '.skill-info{padding:8px 16px;background:#f8fafc;border-bottom:1px solid #e2e8f0;font-size:12px;color:#475569;display:flex;align-items:flex-start;gap:6px}' +
            '.skill-info .skill-icon{flex-shrink:0;color:#6366f1;margin-top:1px}' +
            '.skill-info .skill-text{flex:1;min-width:0}' +
            '.skill-info .skill-name{font-weight:600;color:#3730a3}' +
            '.skill-info .skill-desc{color:#64748b;margin-top:1px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
            '.content{flex:1;overflow-y:auto;padding:12px 16px}' +
            '.msg-user{margin:8px 0;padding:8px 12px;background:#e0e7ff;border-radius:8px;border-left:3px solid #6366f1}' +
            '.msg-ai{margin:8px 0;padding:8px 12px;background:#f3f4f6;border-radius:8px;line-height:1.5;white-space:pre-wrap}' +
            '.msg-err{margin:8px 0;padding:8px 12px;background:#fef2f2;border-radius:8px;color:#dc2626}' +
            '.msg-info{margin:8px 0;padding:4px 8px;color:#6b7280;font-size:12px;text-align:center}' +
            '.input-area{display:flex;gap:8px;padding:12px 16px;border-top:1px solid #e5e7eb}' +
            '.input-area textarea{flex:1;border:1px solid #d1d5db;border-radius:6px;padding:8px 12px;font-size:14px;resize:none;height:40px;max-height:120px;outline:none;font-family:inherit}' +
            '.input-area textarea:focus{border-color:#6366f1;box-shadow:0 0 0 2px rgba(99,102,241,0.1)}' +
            '.input-area button{padding:0 16px;border:none;border-radius:6px;background:#6366f1;color:#fff;font-size:14px;cursor:pointer;font-weight:500}' +
            '.input-area button:hover{background:#4f46e5}' +
            '.input-area button:disabled{background:#c7d2fe;cursor:not-allowed}' +
            '</style>' +
            '<div class="drawer">' +
            '<div class="drawer-handle" id="snap-handle"></div>' +
            '<div class="header">' +
            '  <div class="header-left">' +
            '    <h3 id="snap-title">Anchor Q&A</h3>' +
            '    <div class="subtitle" id="snap-subtitle"></div>' +
            '  </div>' +
            '  <span class="close" id="snap-close">&times;</span>' +
            '</div>' +
            '<div class="skill-info" id="snap-skill-info" style="display:none">' +
            '  <span class="skill-icon"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg></span>' +
            '  <div class="skill-text">' +
            '    <div class="skill-name" id="snap-skill-name"></div>' +
            '    <div class="skill-desc" id="snap-skill-desc"></div>' +
            '  </div>' +
            '</div>' +
            '<div class="content" id="snap-content"></div>' +
            '<div class="input-area"><textarea id="snap-input" placeholder="Ask about this section..." rows="1"></textarea><button id="snap-send">Send</button></div>' +
            '</div>';
    }

    function openDrawer(el, name, skill) {
        createDrawer();
        drawerHost.style.transform = 'translateX(0)';
        drawer.getElementById('snap-title').textContent = name;
        var content = drawer.getElementById('snap-content');
        content.innerHTML = '';

        // Extract content as Markdown
        var markdown = htmlToMarkdown(el.innerHTML.replace(/<div[^>]*class="snap-anchor-icon"[^>]*>[\s\S]*?<\/div>/, ''));
        var maxChars = 8000;
        var truncated = false;
        var originalLength = markdown.length;
        if (markdown.length > maxChars) {
            markdown = markdown.slice(0, maxChars);
            truncated = true;
        }

        // Set subtitle: content summary (first ~80 chars of markdown, single line)
        var summaryText = markdown.replace(/\n/g, ' ').replace(/\s+/g, ' ').trim();
        if (summaryText.length > 80) summaryText = summaryText.slice(0, 80) + '...';
        drawer.getElementById('snap-subtitle').textContent = summaryText;

        // Show skill info if a skill is configured
        var skillInfo = drawer.getElementById('snap-skill-info');
        var skillNameEl = drawer.getElementById('snap-skill-name');
        var skillDescEl = drawer.getElementById('snap-skill-desc');
        if (skill && skill !== 'off' && skill !== '') {
            skillInfo.style.display = 'flex';
            if (skill === 'auto') {
                skillNameEl.textContent = '智能路由 (Auto)';
                skillDescEl.textContent = '根据锚点内容和问题自动匹配最合适的技能';
            } else {
                skillNameEl.textContent = skill;
                skillDescEl.textContent = '已指定技能';
                // Try to fetch skill details from API
                fetchSkillInfo(skill, skillNameEl, skillDescEl);
            }
        } else {
            skillInfo.style.display = 'none';
        }

        // Store anchor context
        drawer._anchorContext = {
            name: name,
            content: markdown,
            truncated: truncated,
            originalLength: originalLength,
            pageUrl: window.location.pathname,
            skill: skill
        };
        drawer._preprocessId = null;

        // Show content preview
        var preview = document.createElement('div');
        preview.className = 'msg-info';
        preview.textContent = 'Content captured (' + markdown.length + ' chars)';
        content.appendChild(preview);

        // Start preprocess (pre-summary + pre-classify)
        if (skill !== 'off') {
            startPreprocess(drawer._anchorContext);
        }
    }

    // ---- Inject mode ----

    function initInjectAnchor(el) {
        var anchorName = el.getAttribute('data-snap-anchor');
        var skillId = el.getAttribute('data-snap-skill');
        var workflowId = el.getAttribute('data-snap-workflow');
        var cacheTtl = parseInt(el.getAttribute('data-snap-cache-ttl') || '3600', 10);
        var fallback = el.getAttribute('data-snap-fallback');

        // Insert loading placeholder
        var loadingEl = document.createElement('div');
        loadingEl.className = 'snap-inject-loading';
        loadingEl.style.cssText = 'display:flex;align-items:center;justify-content:center;gap:8px;min-height:60px;';
        loadingEl.innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="#6366f1" style="animation:snap-blink 1.2s ease-in-out infinite;">'
            + '<path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/>'
            + '</svg>'
            + '<span style="color:#6366f1;font-size:13px;font-family:system-ui,sans-serif;animation:snap-blink 1.2s ease-in-out infinite;">SnapAgent 生成中...</span>'
            + '<style>@keyframes snap-blink{0%,100%{opacity:0.3}50%{opacity:1}}</style>';
        el.innerHTML = '';
        el.appendChild(loadingEl);

        // Fire request
        doInject(el, loadingEl, anchorName, skillId, workflowId, cacheTtl, fallback);
    }

    async function doInject(el, loadingEl, anchorName, skillId, workflowId, cacheTtl, fallback) {
        try {
            var body = {
                anchorName: anchorName,
                pageUrl: window.location.pathname,
                skillId: skillId,
                workflowId: workflowId,
                cacheTtl: cacheTtl
            };
            var resp = await fetch(BASE + '/anchor/inject', {
                method: 'POST',
                headers: Object.assign(
                    { 'Content-Type': 'application/json' }, authHeaders()
                ),
                body: JSON.stringify(body)
            });

            if (!resp.ok) throw new Error('inject failed: ' + resp.status);

            var data = await resp.json();
            loadingEl.outerHTML = data.html;
        } catch (e) {
            if (fallback) {
                loadingEl.outerHTML = fallback;
            } else {
                loadingEl.remove();
            }
        }
    }

    function closeDrawer() {
        if (drawerHost) {
            drawerHost.style.transform = 'translateX(100%)';
            // Abort any ongoing SSE
            if (drawer._eventSource) {
                drawer._eventSource.close();
                drawer._eventSource = null;
            }
        }
    }

    // Fetch skill details (name + description) from the SnapAgent API
    async function fetchSkillInfo(skillId, nameEl, descEl) {
        try {
            var resp = await fetch(BASE + '/skills', { headers: authHeaders() });
            if (!resp.ok) return;
            var data = await resp.json();
            var skills = data.skills || data || [];
            for (var i = 0; i < skills.length; i++) {
                if (skills[i].name === skillId || skills[i].id === skillId) {
                    nameEl.textContent = skills[i].displayName || skills[i].name || skillId;
                    descEl.textContent = skills[i].description || skills[i].desc || '';
                    return;
                }
            }
        } catch (e) { /* keep fallback text */ }
    }

    // ---- Preprocess ----

    async function startPreprocess(anchorContext) {
        try {
            var body = {
                anchor: {
                    name: anchorContext.name,
                    content: anchorContext.content,
                    truncated: anchorContext.truncated,
                    originalLength: anchorContext.originalLength,
                    pageUrl: anchorContext.pageUrl
                }
            };
            var resp = await fetch(BASE + '/anchor/preprocess', {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
                body: JSON.stringify(body)
            });
            if (resp.ok) {
                var data = await resp.json();
                drawer._preprocessId = data.preprocessId;
            }
        } catch (e) { /* preprocess is optional */ }
    }

    // ---- Send message + SSE ----

    async function sendMessage() {
        var input = drawer.getElementById('snap-input');
        var sendBtn = drawer.getElementById('snap-send');
        var question = input.value.trim();
        if (!question || !drawer._anchorContext) return;

        input.value = '';
        sendBtn.disabled = true;

        var content = drawer.getElementById('snap-content');

        // Show user message
        var userMsg = document.createElement('div');
        userMsg.className = 'msg-user';
        userMsg.textContent = question;
        content.appendChild(userMsg);

        // Create AI message placeholder
        var aiMsg = document.createElement('div');
        aiMsg.className = 'msg-ai';
        aiMsg.textContent = '';
        content.appendChild(aiMsg);
        content.scrollTop = content.scrollHeight;

        try {
            var anchor = drawer._anchorContext;
            var skillId = anchor.skill || 'auto';
            var body = {
                skillId: skillId,
                inputs: { message: question },
                anchor: {
                    name: anchor.name,
                    content: anchor.content,
                    truncated: anchor.truncated,
                    originalLength: anchor.originalLength,
                    pageUrl: anchor.pageUrl
                }
            };
            if (drawer._preprocessId) {
                body.preprocessId = drawer._preprocessId;
            }

            var resp = await fetch(BASE + '/runs', {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
                body: JSON.stringify(body)
            });

            if (resp.status === 401) {
                aiMsg.className = 'msg-err';
                aiMsg.textContent = 'Login required';
                sendBtn.disabled = false;
                return;
            }
            if (resp.status === 403) {
                aiMsg.className = 'msg-err';
                aiMsg.textContent = 'Permission denied';
                sendBtn.disabled = false;
                return;
            }
            if (resp.status === 429) {
                aiMsg.className = 'msg-err';
                aiMsg.textContent = 'Rate limited. Please try again later.';
                sendBtn.disabled = false;
                return;
            }
            if (!resp.ok) {
                aiMsg.className = 'msg-err';
                aiMsg.textContent = 'Request failed (' + resp.status + ')';
                sendBtn.disabled = false;
                return;
            }

            var data = await resp.json();
            var taskId = data.taskId || data.id;

            // Stream SSE
            streamSSE(taskId, aiMsg, sendBtn);
        } catch (e) {
            aiMsg.className = 'msg-err';
            aiMsg.textContent = 'Network error: ' + e.message;
            sendBtn.disabled = false;
        }
    }

    function streamSSE(taskId, aiMsg, sendBtn) {
        var eventSource = new EventSource(BASE + '/runs/' + taskId + '/stream', { withCredentials: true });
        drawer._eventSource = eventSource;
        var allText = '';

        eventSource.addEventListener('thought', function (e) {
            try {
                var data = JSON.parse(e.data);
                if (data.text) {
                    allText += data.text;
                    aiMsg.textContent = allText;
                    var content = drawer.getElementById('snap-content');
                    content.scrollTop = content.scrollHeight;
                }
            } catch (err) { /* ignore parse errors */ }
        });

        eventSource.addEventListener('done', function (e) {
            eventSource.close();
            drawer._eventSource = null;
            sendBtn.disabled = false;
        });

        eventSource.addEventListener('error', function () {
            eventSource.close();
            drawer._eventSource = null;
            if (allText) {
                aiMsg.textContent = allText + '\n\n[Connection lost. Resend to retry.]';
            } else {
                aiMsg.className = 'msg-err';
                aiMsg.textContent = 'Connection lost. Please try again.';
            }
            sendBtn.disabled = false;
        });

        eventSource.addEventListener('task_error', function (e) {
            try {
                var data = JSON.parse(e.data);
                aiMsg.className = 'msg-err';
                aiMsg.textContent = 'Error: ' + (data.message || 'unknown error');
            } catch (err) {
                aiMsg.className = 'msg-err';
                aiMsg.textContent = 'Task error';
            }
            eventSource.close();
            drawer._eventSource = null;
            sendBtn.disabled = false;
        });
    }

    // ---- MutationObserver for SPA ----

    function startObserver() {
        var main = document.querySelector('main') || document.body;
        if (!main || !window.MutationObserver) return;
        observer = new MutationObserver(function () {
            if (scanTimer) clearTimeout(scanTimer);
            scanTimer = setTimeout(scanAnchors, 800);
        });
        observer.observe(main, { childList: true, subtree: true });
    }

    // ---- Init ----

    async function init() {
        await loadAuthConfig();
        userAuthorized = await checkUserStatus();
        await loadAnchorConfig();

        if (!anchorConfig.enabled) return;
        if (!userAuthorized) return;

        scanAnchors();
        startObserver();

        // Expose global rescan function for SPA route changes
        window.__SNAP_AGENT_RESCAN__ = scanAnchors;
    }

    // Run on DOMContentLoaded or immediately if already loaded
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
