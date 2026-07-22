// SnapAgent SPA — per-skill independent chat sessions with parallel streams
// Version: v25 (fix: knowledge search uses configured minScore, shows relevance score)
console.log('[SnapAgent] app.js v25 loaded');

const BASE = '/snap-agent';
let selectedSkill = null;            // currently active skill object
let activeSkillName = null;           // name of the visible skill
let skillsData = [];                 // cached skill objects (for restore / icon lookup)
// Per-skill chat state: { [skillName]: { conversationId, transcript, conversationMessages, stream } }
//   transcript entry: { type, content, timestamp, streaming? }
//   stream: { taskId, es, thoughtIdx, thoughtText, allText, pendingRender, done, cancelled, capturedSkillName, thoughtEl } | null
let skillChatState = {};
// Host app active profiles (resolved from /user-info) — injected as environment context
let currentProfiles = [];
let issueClosureEnabled = false; // populated from /user-info; controls visibility of per-message 建议方案/创建 Issue buttons

// ===== Auth config: read token source from server =====
let authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };
let currentUserId = null; // stored from /user-info for SSE token auth

async function loadAuthConfig() {
    try {
        const resp = await fetch(`${BASE}/auth-config`);
        if (resp.ok) {
            authConfig = await resp.json();
        }
    } catch (e) {
        // ignore — fall back to cookie-based auth
    }
}

function getAuthToken() {
    if (authConfig.authLocalStorageKey) {
        try {
            var val = localStorage.getItem(authConfig.authLocalStorageKey);
            if (val) return val;
        } catch (e) { /* localStorage not available */ }
    }
    if (authConfig.authCookie) {
        var match = document.cookie.match(new RegExp('(^|;\\s*)' + authConfig.authCookie + '=([^;]*)'));
        if (match) return decodeURIComponent(match[2]);
    }
    return null;
}

function authHeaders(headers) {
    headers = headers || {};
    if (authConfig.authHeader) {
        const token = getAuthToken();
        if (token && !headers[authConfig.authHeader]) {
            headers[authConfig.authHeader] = token;
        }
    }
    return headers;
}

// ===== Auth: check user status via /user-info =====
async function checkUserStatus() {
    try {
        const resp = await fetch(`${BASE}/user-info`, { headers: authHeaders() });
        if (!resp.ok) {
            showAuthPrompt('登录失效', '请先登录系统后再访问 SnapAgent');
            return false;
        }
        const info = await resp.json();
        if (!info.authenticated) {
            showAuthPrompt('未登录', '请先登录系统后再访问 SnapAgent');
            return false;
        }
        if (!info.authorized) {
            showAuthPrompt('未授权', info.message || '您未授权，请联系管理员授权访问');
            return false;
        }
        if (info.username) {
            document.getElementById('userName').textContent = info.username;
            document.getElementById('userInfo').style.display = 'flex';
        }
        if (info.userId) {
            currentUserId = info.userId;
        }
        // Capture active profiles for environment-context injection
        if (Array.isArray(info.activeProfiles) && info.activeProfiles.length > 0) {
            currentProfiles = info.activeProfiles;
        }
        // Capture issue-closure feature flag for per-message action button visibility
        if (typeof info.issueClosureEnabled === 'boolean') {
            issueClosureEnabled = info.issueClosureEnabled;
        }
        return true;
    } catch (e) {
        showAuthPrompt('网络错误', '无法连接服务器，请检查网络后重试');
        return false;
    }
}

function handleAuthError(resp) {
    if (resp.status === 401) {
        showAuthPrompt('登录失效', '请先登录系统后再访问 SnapAgent');
        return true;
    }
    if (resp.status === 403) {
        showAuthPrompt('无权限', '当前账号无 SnapAgent 访问权限，请联系管理员');
        return true;
    }
    return false;
}

function showAuthPrompt(title, msg) {
    if (document.getElementById('authPrompt')) return;
    const overlay = document.createElement('div');
    overlay.id = 'authPrompt';
    overlay.className = 'auth-prompt';
    overlay.innerHTML =
        '<div class="auth-card">' +
        '<div class="auth-icon">🔒</div>' +
        '<div class="auth-title">' + title + '</div>' +
        '<div class="auth-msg">' + msg + '</div>' +
        '<button class="btn-auth-retry" onclick="location.reload()">刷新重试</button>' +
        '</div>';
    document.body.appendChild(overlay);
}

// ===== Toast =====
function toast(msg, type) {
    type = type || 'success';
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 3000);
}

// ===== Helpers =====
function getSkillState(skillName) {
    if (!skillChatState[skillName]) {
        skillChatState[skillName] = {
            conversationId: null,
            transcript: [],
            conversationMessages: [],
            stream: null,
            // Per-task issue closure state: { taskId: { solutionProposed, issueId, issueStatus } }
            taskIssueStates: {}
        };
    }
    return skillChatState[skillName];
}

function getTaskIssueState(skillName, taskId) {
    if (!taskId) return null;
    const state = getSkillState(skillName);
    if (!state.taskIssueStates[taskId]) {
        state.taskIssueStates[taskId] = { solutionProposed: false, issueId: null, issueStatus: null };
    }
    return state.taskIssueStates[taskId];
}

// Fetch issue states from the backend so per-message badges survive page refresh.
// Matches issues to transcript entries by taskId and populates taskIssueStates.
async function fetchIssueStatesForSkill(skillName) {
    if (!issueClosureEnabled) return;
    const state = getSkillState(skillName);
    const taskIds = new Set();
    (state.transcript || []).forEach(function(t) { if (t.taskId) taskIds.add(t.taskId); });
    if (taskIds.size === 0) return;
    try {
        const resp = await fetch(BASE + '/issues', { headers: authHeaders() });
        if (!resp.ok) return;
        const data = await resp.json();
        const issues = data.issues || [];
        let changed = false;
        issues.forEach(function(ic) {
            const tid = ic.taskId;
            if (!tid || !taskIds.has(tid)) return;
            const issueState = getTaskIssueState(skillName, tid);
            if (ic.status === 'SOLUTION_PROPOSED' || ic.status === 'FIX_IN_PROGRESS'
                || ic.status === 'VERIFIED' || ic.status === 'CLOSED') {
                issueState.solutionProposed = true;
            }
            if (ic.issueId) {
                issueState.issueId = ic.issueId;
                issueState.issueStatus = ic.status;
            }
            changed = true;
        });
        if (changed && activeSkillName === skillName) {
            renderTranscript(skillName);
        }
    } catch (e) {
        console.error('[Issues] fetchIssueStatesForSkill failed:', e);
    }
}

function formatTime(ts) {
    if (!ts) return '';
    const d = new Date(ts);
    const pad = n => String(n).padStart(2, '0');
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

function profileLabel() {
    return currentProfiles.length > 0 ? currentProfiles.join(',') : '';
}

// ===== Load Skills =====
let showDisabledSkills = false;

async function loadSkills() {
    const resp = await fetch(`${BASE}/skills`, { headers: authHeaders() });
    if (handleAuthError(resp)) return;
    const data = await resp.json();
    skillsData = data.skills || [];

    // Split: available vs unavailable, then by source (custom/host vs builtin)
    var available = skillsData.filter(s => s.availability === 'AVAILABLE');
    var unavailable = skillsData.filter(s => s.availability !== 'AVAILABLE');

    var hostSkills = available.filter(s => s.source !== 'builtin');
    var builtinSkills = available.filter(s => s.source === 'builtin');

    var hostUl = document.getElementById('hostSkills');
    var builtinUl = document.getElementById('builtinSkills');
    var disabledUl = document.getElementById('disabledSkills');
    var iconsUl = document.getElementById('skillIcons');
    hostUl.innerHTML = '';
    builtinUl.innerHTML = '';
    disabledUl.innerHTML = '';
    iconsUl.innerHTML = '';

    document.getElementById('skillCount').textContent = available.length;
    document.getElementById('hostCount').textContent = hostSkills.length;
    document.getElementById('builtinCount').textContent = builtinSkills.length;

    // Render a skill item into a <ul>
    function renderSkillItem(ul, skill) {
        var li = document.createElement('li');
        li.dataset.skillId = skill.name;
        var badge = skill.availability === 'AVAILABLE'
            ? '<span class="skill-badge available">可用</span>'
            : '<span class="skill-badge unavailable">不可用</span>';
        li.innerHTML =
            '<div class="skill-item-name">' + escapeHtml(skill.name) + badge +
            '<button class="skill-detail-btn" title="查看 Skill 详情">ℹ️</button>' +
            '<span class="skill-item-running" style="display:none">运行中</span></div>' +
            '<div class="skill-item-desc">' + escapeHtml(skill.description || '') + '</div>';
        if (skill.availability !== 'AVAILABLE') {
            li.title = skill.unavailableReason || skill.availability;
            li.style.opacity = '0.5';
        }
        li.addEventListener('click', function(e) {
            // Don't trigger selectSkill when clicking the detail button
            if (e.target.classList.contains('skill-detail-btn')) return;
            selectSkill(skill, li);
        });
        // Detail button
        var detailBtn = li.querySelector('.skill-detail-btn');
        detailBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            showSkillDetail(skill);
        });
        ul.appendChild(li);

        // Collapsed letter-icon item (only for available skills)
        var iconLi = document.createElement('li');
        iconLi.dataset.skillId = skill.name;
        iconLi.dataset.name = skill.name;
        iconLi.textContent = (skill.name.charAt(0) || '?').toUpperCase();
        iconLi.title = skill.name;
        iconLi.addEventListener('click', function() {
            selectSkill(skill, li);
        });
        iconsUl.appendChild(iconLi);
    }

    // Render host skills (top section, expanded by default)
    hostSkills.forEach(s => renderSkillItem(hostUl, s));

    // Render builtin skills (bottom section, collapsed by default)
    builtinSkills.forEach(s => renderSkillItem(builtinUl, s));

    // Render disabled skills (hidden by default)
    unavailable.forEach(s => {
        var li = document.createElement('li');
        li.dataset.skillId = s.name;
        var badge = '<span class="skill-badge unavailable">不可用</span>';
        li.innerHTML =
            '<div class="skill-item-name">' + escapeHtml(s.name) + badge +
            '<button class="skill-detail-btn" title="查看 Skill 详情">ℹ️</button></div>' +
            '<div class="skill-item-desc">' + escapeHtml(s.description || '') + '</div>';
        li.style.opacity = '0.5';
        li.title = s.unavailableReason || s.availability;
        li.addEventListener('click', function(e) {
            if (e.target.classList.contains('skill-detail-btn')) return;
            selectSkill(s, li);
        });
        var detailBtn = li.querySelector('.skill-detail-btn');
        detailBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            showSkillDetail(s);
        });
        disabledUl.appendChild(li);
    });

    // Section toggle handlers
    document.getElementById('hostSectionHeader').addEventListener('click', function() {
        toggleSection('hostSectionHeader', 'hostSkills');
    });
    document.getElementById('builtinSectionHeader').addEventListener('click', function() {
        toggleSection('builtinSectionHeader', 'builtinSkills');
    });

    // Toggle disabled skills visibility
    document.getElementById('toggleDisabledBtn').addEventListener('click', function() {
        showDisabledSkills = !showDisabledSkills;
        document.getElementById('disabledSkills').style.display = showDisabledSkills ? 'block' : 'none';
        this.classList.toggle('active', showDisabledSkills);
    });

    // Re-apply running indicators for any stream already in progress
    Object.keys(skillChatState).forEach(name => {
        var st = skillChatState[name];
        if (st.stream && !st.stream.done && !st.stream.cancelled) {
            setSkillRunning(name, true);
        }
    });
}

function toggleSection(headerId, listId) {
    var header = document.getElementById(headerId);
    var list = document.getElementById(listId);
    var collapsed = header.dataset.collapsed === 'true';
    if (collapsed) {
        header.dataset.collapsed = 'false';
        header.classList.remove('collapsed');
        header.querySelector('.section-toggle').textContent = '▾';
        list.style.display = 'block';
    } else {
        header.dataset.collapsed = 'true';
        header.classList.add('collapsed');
        header.querySelector('.section-toggle').textContent = '▸';
        list.style.display = 'none';
    }
}

function escapeHtml(text) {
    if (!text) return '';
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function showSkillDetail(skill) {
    // Remove existing modal if any
    var existing = document.getElementById('skillDetailModal');
    if (existing) existing.remove();

    var modal = document.createElement('div');
    modal.className = 'history-modal';
    modal.id = 'skillDetailModal';

    // Build info section
    var infoHtml = '<div class="skill-detail-info">' +
        '<div class="skill-detail-row"><span class="detail-label">名称</span><span class="detail-value">' + escapeHtml(skill.name) + '</span></div>' +
        '<div class="skill-detail-row"><span class="detail-label">来源</span><span class="detail-value">' + escapeHtml(skill.source || 'unknown') + '</span></div>' +
        '<div class="skill-detail-row"><span class="detail-label">状态</span><span class="detail-value">' +
            (skill.availability === 'AVAILABLE'
                ? '<span style="color:var(--green)">可用</span>'
                : '<span style="color:var(--red)">不可用 — ' + escapeHtml(skill.unavailableReason || '') + '</span>') +
            '</span></div>' +
        '<div class="skill-detail-row"><span class="detail-label">描述</span><span class="detail-value">' + escapeHtml(skill.description || '') + '</span></div>' +
        '<div class="skill-detail-row"><span class="detail-label">工具</span><span class="detail-value">' +
            (skill.tools && skill.tools.length > 0 ? escapeHtml(skill.tools.join(', ')) : '无') + '</span></div>';

    // Inputs
    if (skill.inputs && skill.inputs.length > 0) {
        infoHtml += '<div class="skill-detail-row"><span class="detail-label">输入参数</span><div class="detail-inputs">';
        skill.inputs.forEach(function(input) {
            var req = input.required ? '<span class="input-required">必填</span>' : '<span class="input-optional">选填</span>';
            infoHtml += '<div class="detail-input-item">' +
                '<code>' + escapeHtml(input.key) + '</code>' + req +
                ' <span class="input-type">' + escapeHtml(input.type || 'string') + '</span>' +
                ' — ' + escapeHtml(input.label || '') + '</div>';
        });
        infoHtml += '</div></div>';
    }

    // Shortcuts
    if (skill.shortcuts && skill.shortcuts.length > 0) {
        infoHtml += '<div class="skill-detail-row"><span class="detail-label">快捷方式</span><div class="detail-shortcuts">';
        skill.shortcuts.forEach(function(sc) {
            infoHtml += '<div class="detail-shortcut-item"><span class="shortcut-label">' + escapeHtml(sc.label) + '</span>' +
                ' <span class="shortcut-msg">' + escapeHtml(sc.message) + '</span></div>';
        });
        infoHtml += '</div></div>';
    }
    infoHtml += '</div>';

    // Body (original markdown content)
    var bodyHtml = '';
    if (skill.body) {
        bodyHtml = '<div class="skill-detail-body">' +
            '<div class="skill-detail-body-header">📄 Skill 原文 (Markdown)</div>' +
            '<pre class="skill-detail-code">' + escapeHtml(skill.body) + '</pre>' +
            '</div>';
    }

    modal.innerHTML =
        '<div class="history-modal-card" style="width:700px;max-width:90vw;max-height:80vh;">' +
            '<div class="history-modal-header">' +
                '<span class="history-modal-title">Skill 详情: ' + escapeHtml(skill.name) + '</span>' +
                '<button class="history-modal-close" id="skillDetailClose">✕</button>' +
            '</div>' +
            '<div class="history-modal-body">' +
                infoHtml + bodyHtml +
            '</div>' +
        '</div>';

    document.body.appendChild(modal);
    modal.addEventListener('click', function(e) {
        if (e.target === modal || e.target.id === 'skillDetailClose') {
            modal.remove();
        }
    });
}

// ===== Load Models =====
async function loadModels() {
    const resp = await fetch(`${BASE}/models`, { headers: authHeaders() });
    if (handleAuthError(resp)) return;
    const data = await resp.json();
    const select = document.getElementById('modelSelect');
    select.innerHTML = '';
    data.allowed.forEach(m => {
        const opt = document.createElement('option');
        opt.value = m;
        opt.textContent = m;
        if (m === data.default) opt.selected = true;
        select.appendChild(opt);
    });
    const cached = localStorage.getItem('snap-agent.model');
    if (cached && data.allowed.includes(cached)) {
        select.value = cached;
    }
    select.addEventListener('change', () => {
        localStorage.setItem('snap-agent.model', select.value);
    });
}

// ===== Select Skill (no stream cancellation — supports parallel runs) =====
async function selectSkill(skill, li) {
    const skillName = skill.name;
    // Mark previous skill inactive — its stream keeps running in the background.
    // Detach its live DOM references (the DOM will be replaced below).
    if (activeSkillName && activeSkillName !== skillName) {
        const prevStream = skillChatState[activeSkillName] && skillChatState[activeSkillName].stream;
        if (prevStream) prevStream.thoughtEl = null;
    }

    document.querySelectorAll('.skill-list li, .skill-icons li').forEach(el => el.classList.remove('active'));
    if (li) li.classList.add('active');
    const iconLi = document.querySelector(`.skill-icons li[data-skill-id="${CSS.escape(skillName)}"]`);
    if (iconLi) iconLi.classList.add('active');

    selectedSkill = skill;
    activeSkillName = skillName;

    // Load conversation for this skill only if no in-memory state yet (preserves full
    // transcript — thoughts, tool calls — when switching back after running a stream).
    if (!skillChatState[skillName]) {
        const conv = await loadLatestConversation(skillName);
        if (conv) {
            const transcript = (conv.messages || []).map(m => ({
                type: roleToType(m.role),
                content: m.content,
                timestamp: m.timestamp || Date.now(),
                taskId: m.taskId || null
            }));
            skillChatState[skillName] = {
                conversationId: conv.conversationId,
                transcript: transcript,
                conversationMessages: transcriptToLlmHistory(transcript),
                stream: null,
                taskIssueStates: {}
            };
            // Fetch issue states so per-message badges show on restored conversations
            fetchIssueStatesForSkill(skillName);
        } else {
            getSkillState(skillName);
        }
    }
    const state = skillChatState[skillName];

    // Update top bar context
    const ctx = document.getElementById('skillContext');
    let inputsHint = '';
    if (skill.inputs && skill.inputs.length > 0) {
        const required = skill.inputs.filter(i => i.required);
        if (required.length > 0) {
            inputsHint = `<div class="ctx-inputs">⚠️ 必须输入: ${required.map(i => i.label || i.key).join(', ')}</div>`;
        }
    }
    if (skill.appProfiles) {
        inputsHint += `<div class="ctx-logpath">🌍 当前环境: <strong>${skill.appProfiles}</strong></div>`;
    } else if (profileLabel()) {
        inputsHint += `<div class="ctx-logpath">🌍 当前环境: <strong>${profileLabel()}</strong></div>`;
    }
    if (skill.appLogFile) {
        inputsHint += `<div class="ctx-logpath">📄 应用日志: ${skill.appLogFile}</div>`;
    }
    if (skill.logPaths && skill.logPaths.length > 0) {
        inputsHint += `<div class="ctx-logpath">📂 允许目录: ${skill.logPaths.join(', ')}</div>`;
    }
    ctx.innerHTML = `
        <div class="ctx-name">${skill.name}</div>
        <div class="ctx-desc">${skill.description || ''}</div>
        ${inputsHint}
    `;

    document.getElementById('inputArea').style.display = 'block';

    // Render shortcuts
    const shortcutBar = document.getElementById('shortcutBar');
    shortcutBar.innerHTML = '';
    if (skill.shortcuts && skill.shortcuts.length > 0) {
        skill.shortcuts.forEach(sc => {
            const btn = document.createElement('button');
            btn.className = 'shortcut-btn';
            btn.textContent = sc.label;
            btn.addEventListener('click', () => {
                msgInput.value = sc.message;
                msgInput.focus();
                updateSendButtonState();
            });
            shortcutBar.appendChild(btn);
        });
        shortcutBar.style.display = 'flex';
    } else {
        shortcutBar.style.display = 'none';
    }

    // Render input form (auto-fill environment/profile fields)
    const form = document.getElementById('inputForm');
    form.innerHTML = '';
    if (skill.inputs && skill.inputs.length > 0) {
        skill.inputs.forEach(input => {
            const field = document.createElement('div');
            field.className = 'field';
            const label = document.createElement('label');
            label.textContent = input.label || input.key;
            if (input.required) label.textContent += ' *';
            if (input.type === 'enum' && input.options) {
                const sel = document.createElement('select');
                sel.name = input.key;
                sel.dataset.type = input.type;
                input.options.forEach(opt => {
                    const o = document.createElement('option');
                    o.value = opt;
                    o.textContent = opt;
                    sel.appendChild(o);
                });
                // Auto-fill environment-like fields with the active profile
                if (isEnvInputKey(input.key) && profileLabel()) {
                    sel.value = profileLabel().split(',')[0];
                }
                field.appendChild(label);
                field.appendChild(sel);
            } else {
                const inp = document.createElement('input');
                inp.name = input.key;
                inp.dataset.type = input.type;
                inp.type = input.type === 'date' ? 'date' :
                           input.type === 'number' ? 'number' : 'text';
                if (input.required) inp.required = true;
                if (input.default) inp.value = input.default;
                // Auto-fill environment-like fields with the active profile
                if (isEnvInputKey(input.key) && profileLabel()) {
                    inp.value = profileLabel();
                }
                field.appendChild(label);
                field.appendChild(inp);
            }
            form.appendChild(field);
        });
    }

    // Rebuild chat messages from the full in-memory transcript (thoughts + tool calls preserved)
    renderTranscript(skillName);

    document.getElementById('messageInput').focus();
    updateSendButtonState();
}

function isEnvInputKey(key) {
    if (!key) return false;
    const k = key.toLowerCase();
    return k === 'profile' || k === 'profiles' || k === 'environment' || k === 'env';
}

// ===== Render transcript (rebuild DOM from per-skill state) =====
function renderTranscript(skillName) {
    const state = getSkillState(skillName);
    const chatMessages = document.getElementById('chatMessages');
    const chatWelcome = document.getElementById('chatWelcome');
    chatMessages.innerHTML = '';
    // Reset live element refs — they will be re-attached below for any streaming entry
    if (state.stream) state.stream.thoughtEl = null;
    const hasContent = state.transcript.length > 0;
    if (hasContent) {
        chatWelcome.style.display = 'none';
    } else {
        chatWelcome.style.display = 'flex';
    }
    state.transcript.forEach((entry, idx) => {
        const el = renderMessageEl(entry, skillName);
        chatMessages.appendChild(el);
        if (entry.streaming && state.stream) {
            state.stream.thoughtIdx = idx;
            state.stream.thoughtEl = el;
        }
    });
    // If this skill has an active (not done) stream, show the working indicator
    if (state.stream && !state.stream.done && !state.stream.cancelled) {
        showRobotWorking();
    } else {
        hideRobotWorking();
    }
    scrollChat();
}

// ===== Build a single message DOM element from a transcript entry =====
function renderMessageEl(entry, skillName) {
    const el = document.createElement('div');
    el.className = `msg msg-${entry.type}`;
    const labelMap = {
        'thought': '思考',
        'response': '回复',
        'tool-call': '工具调用',
        'tool-result': '工具结果',
        'error': '错误',
        'user': '你'
    };
    if (entry.type === 'completion') {
        // Subtle centered divider — no meta row
        const contentEl = document.createElement('div');
        contentEl.className = 'msg-content';
        contentEl.textContent = entry.content || '';
        el.appendChild(contentEl);
        return el;
    }
    if (labelMap[entry.type]) {
        const meta = document.createElement('div');
        meta.className = 'msg-meta';
        const label = document.createElement('span');
        label.className = 'msg-label';
        label.textContent = labelMap[entry.type];
        meta.appendChild(label);
        if (entry.timestamp) {
            const ts = document.createElement('span');
            ts.className = 'msg-timestamp';
            ts.textContent = formatTime(entry.timestamp);
            meta.appendChild(ts);
        }
        el.appendChild(meta);
    }
    const contentEl = document.createElement('div');
    contentEl.className = 'msg-content';
    if (entry.type === 'response' || entry.type === 'thought') {
        try {
            contentEl.innerHTML = renderMarkdown(entry.content || '');
        } catch (err) {
            contentEl.textContent = entry.content || '';
        }
    } else {
        contentEl.textContent = entry.content || '';
    }
    el.appendChild(contentEl);
    if (entry.streaming) {
        el.classList.add('stream-cursor');
    }
    // Append per-message action buttons for finalized responses tied to a task
    if (entry.type === 'response' && entry.taskId && !entry.streaming) {
        appendMessageActions(el, entry, skillName || entry.skillName);
    }
    return el;
}

// ===== Decide whether a response has substantive diagnostic content (for "创建 Issue" visibility) =====
function responseHasDiagnosticContent(entry, transcript, idx) {
    if (!entry || entry.type !== 'response') return false;
    const content = (entry.content || '').toLowerCase();
    const text = entry.content || '';

    // Heuristic 1: response mentions root cause / solution / problem keywords
    const diagnosticKeywords = [
        '根因', '原因', '问题', '建议', '方案', '修复', '解决', '异常', '错误', '风险', '排查', '诊断',
        'root cause', 'issue', 'fix', 'solution', 'error', 'exception', 'failure', 'recommendation'
    ];
    const contentLower = content.toLowerCase();
    if (diagnosticKeywords.some(k => contentLower.indexOf(k) >= 0)) return true;

    // Heuristic 2: response is non-trivial (>= 120 chars) AND there were tool calls since the preceding user message
    if (text.length >= 120) {
        // Walk backwards from idx-1 to find the last user message; check for tool_call entries in between
        for (let i = idx - 1; i >= 0; i--) {
            const prev = transcript[i];
            if (!prev) break;
            if (prev.type === 'tool-call' || prev.type === 'tool-result') return true;
            if (prev.type === 'user') break;
        }
    }

    // Heuristic 3: the preceding user message contains problem-report keywords
    for (let i = idx - 1; i >= 0; i--) {
        const prev = transcript[i];
        if (!prev) break;
        if (prev.type === 'user') {
            const q = (prev.content || '').toLowerCase();
            const problemKeywords = [
                '错误', '异常', '失败', '报错', '不行', '不工作', '没生效', '无效', '慢', '挂了', '卡', '不对',
                '为什么', '怎么办', '排查', '诊断', '帮忙', '看看', '查下', '查一下',
                'error', 'exception', 'failed', 'broken', 'slow', 'down', 'why', 'help', 'investigate'
            ];
            return problemKeywords.some(k => q.indexOf(k) >= 0);
        }
        // Don't cross tool calls — only look at the nearest preceding user message
        if (prev.type === 'response' || prev.type === 'thought') continue;
    }
    return false;
}

// ===== Append per-message action buttons (复制 / 建议方案 / 创建 Issue) =====
function appendMessageActions(msgEl, entry, skillName) {
    const taskId = entry.taskId;
    if (!taskId) return;
    const sn = skillName || entry.skillName || activeSkillName;
    if (!sn) return;
    const state = getSkillState(sn);
    const issueState = getTaskIssueState(sn, taskId);

    // If issue already created, show a status badge instead of buttons
    const existing = msgEl.querySelector('.msg-actions');
    if (existing) existing.remove();

    const actionsEl = document.createElement('div');
    actionsEl.className = 'msg-actions';

    // 📋 复制 — always available
    const copyBtn = document.createElement('button');
    copyBtn.className = 'msg-action-btn';
    copyBtn.type = 'button';
    copyBtn.textContent = '📋 复制';
    copyBtn.setAttribute('data-action', 'copy');
    copyBtn.setAttribute('data-task-id', taskId);
    copyBtn.title = '复制本条消息内容';
    actionsEl.appendChild(copyBtn);

    // 💡 建议方案 — visible if issue-closure enabled and solution not yet proposed
    // (issueClosureEnabled flag is set globally from /user-info; default true if unknown)
    if (issueClosureEnabled !== false && !issueState.solutionProposed && !issueState.issueId) {
        const solBtn = document.createElement('button');
        solBtn.className = 'msg-action-btn';
        solBtn.type = 'button';
        solBtn.textContent = '💡 建议方案';
        solBtn.setAttribute('data-action', 'propose-solution');
        solBtn.setAttribute('data-task-id', taskId);
        solBtn.setAttribute('data-skill-name', sn);
        solBtn.title = '基于本次诊断生成修复方案';
        actionsEl.appendChild(solBtn);
    } else if (issueState.solutionProposed && !issueState.issueId) {
        const badge = document.createElement('span');
        badge.className = 'msg-action-badge success';
        badge.textContent = '✓ 方案已生成';
        actionsEl.appendChild(badge);
    }

    // 🐛 创建 Issue — visible only if diagnostic content present and issue not yet created
    const transcript = state.transcript;
    const idx = transcript.indexOf(entry);
    const hasContent = idx >= 0 && responseHasDiagnosticContent(entry, transcript, idx);
    if (issueClosureEnabled !== false && hasContent && !issueState.issueId) {
        const issBtn = document.createElement('button');
        issBtn.className = 'msg-action-btn';
        issBtn.type = 'button';
        issBtn.textContent = '🐛 创建 Issue';
        issBtn.setAttribute('data-action', 'create-issue');
        issBtn.setAttribute('data-task-id', taskId);
        issBtn.setAttribute('data-skill-name', sn);
        issBtn.title = '基于本次诊断创建外部 Issue';
        actionsEl.appendChild(issBtn);
    } else if (issueState.issueId) {
        const badge = document.createElement('span');
        badge.className = 'msg-action-badge success';
        badge.textContent = '✓ Issue ' + (issueState.issueStatus ? '(' + issueState.issueStatus + ')' : '');
        badge.title = issueState.issueId;
        actionsEl.appendChild(badge);
    }

    msgEl.appendChild(actionsEl);
}

// ===== Append a transcript entry; render to live DOM only if the skill is active =====
function appendTranscript(skillName, entry) {
    const state = getSkillState(skillName);
    state.transcript.push(entry);
    if (activeSkillName === skillName) {
        hideRobotWorking();
        document.getElementById('chatMessages').appendChild(renderMessageEl(entry, skillName));
        // Re-show working indicator if the stream is still running after a tool call/result
        const st = skillChatState[skillName];
        if (st.stream && !st.stream.done && !st.stream.cancelled) {
            showRobotWorking();
        }
        scrollChat();
    }
}

// ===== Finalize the in-progress streaming entry (demote to thought or keep as response) =====
function finalizeStreamingThought(streamState, toType) {
    if (streamState.thoughtIdx === null) return;
    const state = getSkillState(streamState.capturedSkillName);
    const entry = state.transcript[streamState.thoughtIdx];
    if (entry) {
        entry.streaming = false;
        entry.type = toType; // 'thought' or 'response'
        // Stamp taskId on the final response so per-message action buttons can target it
        if (toType === 'response' && streamState.taskId) {
            entry.taskId = streamState.taskId;
            entry.skillName = streamState.capturedSkillName;
        }
    }
    // Update the live DOM element if the skill is currently visible
    if (activeSkillName === streamState.capturedSkillName && streamState.thoughtEl) {
        const el = streamState.thoughtEl;
        el.className = 'msg msg-' + toType;
        el.classList.remove('stream-cursor');
        const labelEl = el.querySelector('.msg-label');
        if (labelEl) labelEl.textContent = toType === 'thought' ? '思考' : '回复';
        const contentEl = el.querySelector('.msg-content');
        if (contentEl) {
            try {
                contentEl.innerHTML = renderMarkdown(streamState.thoughtText);
            } catch (err) {
                contentEl.textContent = streamState.thoughtText;
            }
        }
        // If finalized as response with taskId, append per-message action buttons
        if (toType === 'response' && streamState.taskId) {
            appendMessageActions(el, entry, streamState.capturedSkillName);
        }
    }
    streamState.thoughtIdx = null;
    streamState.thoughtEl = null;
}

// ===== Send / Run =====
async function runSkill() {
    if (!selectedSkill) return;
    const skillName = selectedSkill.name;
    const state = getSkillState(skillName);

    // Collect inputs from form fields
    const inputs = {};
    const form = document.getElementById('inputForm');
    form.querySelectorAll('input, select, textarea').forEach(field => {
        if (field.name) inputs[field.name] = field.value;
    });

    // Get message from input bar
    const msgInputEl = document.getElementById('messageInput');
    const message = msgInputEl.value.trim();
    if (message) {
        if ('message' in inputs && !inputs.message) {
            inputs.message = message;
        } else if (!('message' in inputs)) {
            inputs._user_message = message;
        }
        msgInputEl.value = '';
        msgInputEl.style.height = 'auto';
    }

    if (Object.keys(inputs).length === 0) return;

    const model = document.getElementById('modelSelect').value;

    const displayMsg = inputs.message || inputs._user_message || Object.values(inputs).join(', ');
    appendTranscript(skillName, { type: 'user', content: displayMsg, timestamp: Date.now() });
    state.conversationMessages.push({ role: 'user', content: displayMsg });
    // Save immediately so the user message is persisted before the stream starts
    // (ensures it survives a page refresh during streaming)
    saveConversationToBackend(skillName);

    showRobotWorking();
    sendBtn.disabled = true;

    console.log('[runSkill] Sending POST /runs, skillId=', skillName, 'model=', model);

    try {
        const resp = await fetch(`${BASE}/runs`, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({
                skillId: skillName,
                inputs,
                model,
                history: state.conversationMessages.length > 1 ? state.conversationMessages.slice(0, -1) : []
            })
        });
        if (handleAuthError(resp)) { hideRobotWorking(); updateSendButtonState(); return; }

        // Handle rate limit (429): don't cancel the existing stream — show error and keep it running
        if (resp.status === 429) {
            hideRobotWorking();
            appendTranscript(skillName, { type: 'error', content: '⏱ 频率限制：当前已有任务在运行，请等待完成后再发起新请求', timestamp: Date.now() });
            updateSendButtonState();
            return;
        }

        const data = await resp.json();
        console.log('[runSkill] POST /runs response:', data.status, 'taskId=', data.taskId);
        if (data.taskId) {
            // Cancel the old stream ONLY after the new run is successfully created
            if (state.stream) {
                cancelSkillStream(skillName, true);
            }
            subscribeStream(data.taskId, skillName);
        } else if (data.error) {
            hideRobotWorking();
            appendTranscript(skillName, { type: 'error', content: data.message || data.error, timestamp: Date.now() });
        }
    } catch (e) {
        console.error('[runSkill] POST /runs failed:', e);
        hideRobotWorking();
        appendTranscript(skillName, { type: 'error', content: '请求失败: ' + e.message, timestamp: Date.now() });
    } finally {
        updateSendButtonState();
    }
}

// ===== Cancel a specific skill's stream (stops event processing, saves partial) =====
function cancelSkillStream(skillName, savePartial) {
    const state = getSkillState(skillName);
    const streamState = state.stream;
    if (!streamState) return;

    // Call server to cancel the task (interrupts in-flight LLM HTTP call)
    if (streamState.taskId) {
        fetch(`${BASE}/runs/${streamState.taskId}/cancel`, {
            method: 'POST',
            headers: authHeaders()
        }).catch(function(e) {
            console.error('[cancel] Failed to cancel task:', e);
        });
    }

    streamState.cancelled = true;
    // Finalize any streaming thought to stop the blinking cursor
    finalizeStreamingThought(streamState, 'thought');
    if (streamState.es) {
        try { streamState.es.close(); } catch (e) { /* ignore */ }
    }
    if (savePartial && streamState.allText) {
        const msgs = state.conversationMessages;
        if (msgs.length === 0 || msgs[msgs.length - 1].role !== 'assistant') {
            msgs.push({ role: 'assistant', content: streamState.allText });
            saveConversationToBackend(skillName);
        }
    }
    // Show interrupted indicator (only if the stream had actual content or was mid-stream)
    if (streamState.thoughtIdx !== null || streamState.allText) {
        appendTranscript(skillName, { type: 'completion', content: '— 已中断 —', timestamp: Date.now() });
    }
    if (activeSkillName === skillName) hideRobotWorking();
    setSkillRunning(skillName, false);
    state.stream = null;
}

// ===== Stream Subscription (per-skill, independent) =====
function subscribeStream(taskId, skillName) {
    const state = getSkillState(skillName);
    let streamState = state.stream;
    if (!streamState) {
        streamState = {
            taskId: taskId,
            es: null,
            thoughtIdx: null,
            thoughtText: '',
            allText: '',
            pendingRender: false,
            done: false,
            cancelled: false,
            capturedSkillName: skillName,
            thoughtEl: null
        };
        state.stream = streamState;
    } else {
        // Reuse the slot but reset transient fields
        streamState.taskId = taskId;
        streamState.thoughtIdx = null;
        streamState.thoughtText = '';
        streamState.allText = '';
        streamState.pendingRender = false;
        streamState.done = false;
        streamState.cancelled = false;
        streamState.thoughtEl = null;
    }

    setSkillRunning(skillName, true);

    let url = `${BASE}/runs/${taskId}/stream`;
    if (currentUserId) {
        const tokenParam = btoa(currentUserId + ':x');
        url += `?token=${encodeURIComponent(tokenParam)}`;
    }
    console.log('[subscribeStream] Creating EventSource for taskId=', taskId, 'skill=', skillName);
    const es = new EventSource(url);
    streamState.es = es;

    es.onopen = function() {
        if (streamState.cancelled) return;
        console.log('[SSE] connection opened for', skillName);
        if (activeSkillName === skillName) showRobotWorking();
    };

    es.addEventListener('thought', e => {
        if (streamState.cancelled) return;
        try {
            const data = JSON.parse(e.data);
            if (data.text) {
                streamState.allText += data.text;
                // First thought token: create the streaming transcript entry
                if (streamState.thoughtIdx === null) {
                    const entry = { type: 'response', content: '', timestamp: Date.now(), streaming: true };
                    state.transcript.push(entry);
                    streamState.thoughtIdx = state.transcript.length - 1;
                    if (activeSkillName === skillName) {
                        hideRobotWorking();
                        const el = renderMessageEl(entry);
                        document.getElementById('chatMessages').appendChild(el);
                        streamState.thoughtEl = el;
                        scrollChat();
                    }
                }
                streamState.thoughtText += data.text;
                // Update transcript content so a later renderTranscript reflects the latest text
                if (streamState.thoughtIdx !== null) {
                    state.transcript[streamState.thoughtIdx].content = streamState.thoughtText;
                }
                // Debounced live DOM update when visible
                if (activeSkillName === skillName && streamState.thoughtEl && !streamState.pendingRender) {
                    streamState.pendingRender = true;
                    requestAnimationFrame(() => {
                        streamState.pendingRender = false;
                        if (streamState.cancelled || !streamState.thoughtEl) return;
                        const contentEl = streamState.thoughtEl.querySelector('.msg-content');
                        if (!contentEl) return;
                        try {
                            contentEl.innerHTML = renderMarkdown(streamState.thoughtText);
                        } catch (err) {
                            contentEl.textContent = streamState.thoughtText;
                        }
                        scrollChat();
                    });
                }
            }
        } catch (err) { /* ignore parse errors */ }
    });

    es.addEventListener('tool_call', e => {
        if (streamState.cancelled) return;
        // Finalize any in-progress thought as a "思考" block
        finalizeStreamingThought(streamState, 'thought');
        if (activeSkillName === skillName) showRobotWorking();
        try {
            const data = JSON.parse(e.data);
            appendTranscript(skillName, {
                type: 'tool-call',
                content: `🔧 ${data.name}\n${JSON.stringify(data.args, null, 2)}`,
                timestamp: Date.now()
            });
        } catch (err) {
            appendTranscript(skillName, { type: 'tool-call', content: e.data, timestamp: Date.now() });
        }
    });

    es.addEventListener('tool_result', e => {
        if (streamState.cancelled) return;
        finalizeStreamingThought(streamState, 'thought');
        try {
            const data = JSON.parse(e.data);
            let display = '';
            if (data.error) {
                display = `❌ 错误: ${data.error}\n⏱ ${data.durationMs}ms`;
            } else if (data.content) {
                display = `📋 ${data.rowCount} 行 (${data.durationMs}ms${data.truncated ? ', 截断' : ''})\n${data.content}`;
            } else {
                display = `📋 ${data.rowCount} 行 (${data.durationMs}ms)`;
            }
            appendTranscript(skillName, { type: 'tool-result', content: display, timestamp: Date.now() });
        } catch (err) {
            appendTranscript(skillName, { type: 'tool-result', content: e.data, timestamp: Date.now() });
        }
        if (activeSkillName === skillName) showRobotWorking();
    });

    es.addEventListener('done', e => {
        console.log('[SSE] done event for', skillName, 'data:', e.data);
        if (streamState.cancelled) {
            try { es.close(); } catch (err) {}
            state.stream = null;
            setSkillRunning(skillName, false);
            return;
        }
        streamState.done = true;
        if (activeSkillName === skillName) hideRobotWorking();
        try {
            // Keep the in-progress entry as the final "回复"
            finalizeStreamingThought(streamState, 'response');
            let status = 'SUCCEEDED';
            let report = '';
            try {
                const data = JSON.parse(e.data);
                status = data.status || 'SUCCEEDED';
                report = data.report || '';
            } catch (err) {
                if (e.data && e.data.trim()) status = e.data.trim();
            }
            // Build terminal message — prominent for non-SUCCEEDED
            let completionContent, completionType = 'completion';
            if (status === 'SUCCEEDED') {
                completionContent = `— 完成 —`;
            } else if (status === 'TIMEOUT') {
                completionContent = `⚠ 任务超时：${report || '已达最大轮次上限，诊断未能完成'}`;
                completionType = 'error';
            } else if (status === 'FAILED') {
                completionContent = `❌ 任务失败${report ? '：' + report : ''}`;
                completionType = 'error';
            } else if (status === 'CANCELLED') {
                completionContent = `— 已取消 —`;
            } else {
                completionContent = `— ${status} —`;
            }
            appendTranscript(skillName, { type: completionType, content: completionContent, timestamp: Date.now() });
            // Save last task info for chat action bar
            state.lastTaskId = streamState.taskId;
            state.lastTaskStatus = status;
            state.lastTaskIssueId = null;
            // Save assistant content to conversation history
            if (streamState.allText) {
                const msgs = state.conversationMessages;
                if (msgs.length === 0 || msgs[msgs.length - 1].role !== 'assistant') {
                    msgs.push({ role: 'assistant', content: streamState.allText });
                }
            }
            saveConversationToBackend(skillName);
        } catch (doneErr) {
            console.error('[SSE] done handler error:', doneErr);
        } finally {
            try { es.close(); } catch (err) {}
            state.stream = null;
            setSkillRunning(skillName, false);
            if (activeSkillName === skillName) {
                updateSendButtonState();
                // Per-message action buttons are already appended by finalizeStreamingThought above
                // when the streaming thought is converted to a final 'response' entry.
            }
        }
    });

    es.addEventListener('task_error', e => {
        if (streamState.cancelled) return;
        console.log('[SSE] task_error for', skillName);
        if (activeSkillName === skillName) hideRobotWorking();
        finalizeStreamingThought(streamState, 'thought');
        try {
            const data = JSON.parse(e.data);
            appendTranscript(skillName, { type: 'error', content: data.text || '任务执行出错', timestamp: Date.now() });
        } catch (err) {
            appendTranscript(skillName, { type: 'error', content: e.data || '任务执行出错', timestamp: Date.now() });
        }
        // Don't close ES or null stream — the 'done' event will handle cleanup
    });

    es.addEventListener('error', e => {
        console.log('[SSE] error event for', skillName, 'readyState:', es.readyState);
        if (streamState.cancelled) return;
        if (streamState.done) return; // already finalized by done handler
        if (activeSkillName === skillName) hideRobotWorking();
        // Finalize the in-progress entry as a thought (partial)
        finalizeStreamingThought(streamState, 'thought');
        // Save partial conversation
        if (streamState.allText) {
            const msgs = state.conversationMessages;
            if (msgs.length === 0 || msgs[msgs.length - 1].role !== 'assistant') {
                msgs.push({ role: 'assistant', content: streamState.allText });
                saveConversationToBackend(skillName);
            }
        }
        appendTranscript(skillName, { type: 'completion', content: '— 连接断开，请重新发送消息继续 —', timestamp: Date.now() });
        try { es.close(); } catch (err) {}
        state.stream = null;
        setSkillRunning(skillName, false);
        if (activeSkillName === skillName) updateSendButtonState();
    });
}

// ===== Sidebar running indicator =====
function setSkillRunning(skillName, running) {
    const expanded = document.querySelector(`.skill-list li[data-skill-id="${CSS.escape(skillName)}"]`);
    const collapsed = document.querySelector(`.skill-icons li[data-skill-id="${CSS.escape(skillName)}"]`);
    const cls = 'has-running-stream';
    if (expanded) expanded.classList.toggle(cls, running);
    if (collapsed) collapsed.classList.toggle(cls, running);
    [expanded, collapsed].forEach(el => {
        if (!el) return;
        const badge = el.querySelector('.skill-item-running');
        if (badge) badge.style.display = running ? 'inline-flex' : 'none';
    });
}

// ===== Scroll =====
function scrollChat() {
    const chatArea = document.getElementById('chatArea');
    chatArea.scrollTop = chatArea.scrollHeight;
}

// ===== Upload (single .md or .zip) =====
document.getElementById('uploadInput').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);
    try {
        const resp = await fetch(`${BASE}/skills/upload`, {
            method: 'POST',
            headers: authHeaders(),
            body: formData
        });
        if (handleAuthError(resp)) return;
        const data = await resp.json();
        if (data.error) {
            toast(data.message || '上传失败', 'error');
        } else {
            toast(`上传成功: ${data.filename} (${data.available} 可用)`, 'success');
            await loadSkills();
        }
    } catch (err) {
        toast('上传失败: ' + err.message, 'error');
    }
    e.target.value = '';
});

// ===== Upload Folder =====
document.getElementById('uploadFolderInput').addEventListener('change', async (e) => {
    const files = Array.from(e.target.files);
    if (!files.length) return;
    const firstPath = files[0].webkitRelativePath || files[0].name;
    const dirName = firstPath.split('/')[0];
    const formData = new FormData();
    formData.append('dirName', dirName);
    files.forEach(f => formData.append('files', f, f.webkitRelativePath || f.name));
    try {
        const resp = await fetch(`${BASE}/skills/upload-folder`, {
            method: 'POST',
            headers: authHeaders(),
            body: formData
        });
        if (handleAuthError(resp)) return;
        const data = await resp.json();
        if (data.error) {
            toast(data.message || '上传失败', 'error');
        } else {
            toast(`上传成功: ${dirName} (${data.filesSaved} 文件, ${data.available} 可用)`, 'success');
            await loadSkills();
        }
    } catch (err) {
        toast('上传失败: ' + err.message, 'error');
    }
    e.target.value = '';
});

// ===== Refresh =====
document.getElementById('refreshBtn').addEventListener('click', async () => {
    const btn = document.getElementById('refreshBtn');
    btn.style.opacity = '0.6';
    try {
        const resp = await fetch(`${BASE}/skills/refresh`, { method: 'POST', headers: authHeaders() });
        if (handleAuthError(resp)) return;
        await loadSkills();
        toast('Skills 已刷新', 'success');
    } catch (e) {
        toast('刷新失败: ' + e.message, 'error');
    }
    btn.style.opacity = '1';
});

// ===== History Button =====
document.getElementById('historyBtn').addEventListener('click', showHistoryModal);

// ===== Conversation History (save, load, list, download, delete) =====

async function saveConversationToBackend(skillName) {
    const state = skillChatState[skillName];
    if (!state) return;
    // Save the full transcript (not just user/assistant messages) so that
    // tool calls, errors, and completions are restored on page refresh.
    if (!state.transcript || state.transcript.length === 0) return;
    try {
        const resp = await fetch(`${BASE}/conversations`, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({
                conversationId: state.conversationId,
                skillId: skillName,
                messages: state.transcript.map(t => ({
                    role: t.type,
                    content: t.content,
                    timestamp: t.timestamp || Date.now(),
                    taskId: t.taskId || null
                }))
            })
        });
        if (resp.ok) {
            const data = await resp.json();
            state.conversationId = data.conversationId;
            console.log('[Conversation] saved:', data.conversationId, 'messages:', data.messageCount);
        }
    } catch (e) {
        console.error('[Conversation] save failed:', e);
    }
}

// Map stored conversation message roles to transcript types.
// Handles both new format (role=type) and legacy format (role=assistant).
function roleToType(role) {
    if (role === 'assistant') return 'response';
    return role;
}

// Reconstruct conversationMessages (user/assistant only, for LLM history) from a loaded transcript
function transcriptToLlmHistory(transcript) {
    return (transcript || [])
        .filter(t => t.type === 'user' || t.type === 'response')
        .map(t => ({
            role: t.type === 'user' ? 'user' : 'assistant',
            content: t.content
        }));
}

async function loadLatestConversation(skillId) {
    try {
        const resp = await fetch(`${BASE}/conversations?skillId=${encodeURIComponent(skillId)}`,
            { headers: authHeaders() });
        if (!resp.ok) return null;
        const data = await resp.json();
        if (!data.conversations || data.conversations.length === 0) return null;
        const convId = data.conversations[0].conversationId;
        return await loadConversationById(convId);
    } catch (e) {
        console.error('[Conversation] loadLatest failed:', e);
        return null;
    }
}

async function loadConversationById(conversationId) {
    try {
        const resp = await fetch(`${BASE}/conversations/${conversationId}`,
            { headers: authHeaders() });
        if (!resp.ok) return null;
        const data = await resp.json();
        return {
            conversationId: data.conversationId,
            skillId: data.skillId,
            messages: (data.messages || []).map(m => ({
                role: m.role,
                content: m.content,
                timestamp: m.timestamp,
                taskId: m.taskId || null
            }))
        };
    } catch (e) {
        console.error('[Conversation] load failed:', e);
        return null;
    }
}

async function showHistoryModal() {
    const existing = document.getElementById('historyModal');
    if (existing) existing.remove();

    const overlay = document.createElement('div');
    overlay.id = 'historyModal';
    overlay.className = 'history-modal';

    const modal = document.createElement('div');
    modal.className = 'history-modal-card';

    modal.innerHTML = `
        <div class="history-modal-header">
            <span class="history-modal-title">📜 历史会话${selectedSkill ? ' — ' + selectedSkill.name : ''}</span>
            <button class="history-modal-close" id="historyCloseBtn">✕</button>
        </div>
        <div class="history-modal-body" id="historyModalBody">
            <div class="history-loading">加载中...</div>
        </div>
    `;
    overlay.appendChild(modal);
    document.body.appendChild(overlay);

    overlay.addEventListener('click', (e) => {
        if (e.target === overlay) overlay.remove();
    });
    document.getElementById('historyCloseBtn').addEventListener('click', () => overlay.remove());

    const body = document.getElementById('historyModalBody');
    try {
        let url = `${BASE}/conversations`;
        if (selectedSkill) {
            url += `?skillId=${encodeURIComponent(selectedSkill.name)}`;
        }
        const resp = await fetch(url, { headers: authHeaders() });
        if (!resp.ok) {
            body.innerHTML = '<div class="history-error">加载失败</div>';
            return;
        }
        const data = await resp.json();
        const conversations = data.conversations || [];
        if (conversations.length === 0) {
            body.innerHTML = '<div class="history-empty">暂无历史会话</div>';
            return;
        }

        body.innerHTML = '';
        conversations.forEach(conv => {
            const item = document.createElement('div');
            item.className = 'history-item';
            const date = new Date(conv.updatedAt || conv.createdAt);
            const dateStr = date.toLocaleString('zh-CN');
            item.innerHTML = `
                <div class="history-item-info">
                    <div class="history-item-title">${conv.title || '未命名对话'}</div>
                    <div class="history-item-meta">
                        <span class="history-item-date">${dateStr}</span>
                        <span class="history-item-count">${conv.messageCount} 条消息</span>
                    </div>
                </div>
                <div class="history-item-actions">
                    <button class="history-btn history-btn-load" title="加载此会话">📂 加载</button>
                    <button class="history-btn history-btn-download" title="下载为 Markdown">⬇ 下载</button>
                    <button class="history-btn history-btn-delete" title="删除">🗑 删除</button>
                </div>
            `;
            item.querySelector('.history-btn-load').addEventListener('click', async () => {
                overlay.remove();
                await restoreConversation(conv.conversationId, conv.skillId);
            });
            item.querySelector('.history-btn-download').addEventListener('click', () => {
                downloadConversation(conv.conversationId);
            });
            item.querySelector('.history-btn-delete').addEventListener('click', async () => {
                if (!confirm('确认删除此会话？')) return;
                await deleteConversationById(conv.conversationId);
                showHistoryModal();
            });
            body.appendChild(item);
        });
    } catch (e) {
        body.innerHTML = '<div class="history-error">加载失败: ' + e.message + '</div>';
    }
}

async function restoreConversation(conversationId, skillId) {
    const skill = skillsData.find(s => s.name === skillId);
    if (!skill) {
        toast('找不到对应的 Skill: ' + skillId, 'error');
        return;
    }
    const conv = await loadConversationById(conversationId);
    if (!conv) {
        toast('加载会话失败', 'error');
        return;
    }
    // Cancel any active stream for the target skill before replacing its state
    if (skillChatState[skillId] && skillChatState[skillId].stream) {
        cancelSkillStream(skillId, false);
    }
    // Overwrite the in-memory state with the restored conversation
    const transcript = (conv.messages || []).map(m => ({
        type: roleToType(m.role),
        content: m.content,
        timestamp: m.timestamp || Date.now(),
        taskId: m.taskId || null
    }));
    skillChatState[skillId] = {
        conversationId: conv.conversationId,
        transcript: transcript,
        conversationMessages: transcriptToLlmHistory(transcript),
        stream: null,
        taskIssueStates: {}
    };
    const skillLi = document.querySelector(`.skill-list li[data-skill-id="${CSS.escape(skillId)}"]`);
    selectSkill(skill, skillLi);
    // Fetch issue states so per-message badges show on restored conversations
    fetchIssueStatesForSkill(skillId);
    toast('会话已加载', 'success');
}

function downloadConversation(conversationId) {
    fetch(`${BASE}/conversations/${conversationId}/download`, {
        headers: authHeaders()
    }).then(resp => {
        if (!resp.ok) throw new Error('Download failed');
        const cd = resp.headers.get('Content-Disposition') || '';
        let filename = 'conversation.md';
        const match = cd.match(/filename\*=UTF-8''([^;]+)/);
        if (match) {
            filename = decodeURIComponent(match[1]);
        } else {
            const match2 = cd.match(/filename="([^"]+)"/);
            if (match2) filename = match2[1];
        }
        return resp.blob().then(blob => ({ blob, filename }));
    }).then(({ blob, filename }) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }).catch(e => {
        toast('下载失败: ' + e.message, 'error');
    });
}

async function deleteConversationById(conversationId) {
    try {
        const resp = await fetch(`${BASE}/conversations/${conversationId}`, {
            method: 'DELETE',
            headers: authHeaders()
        });
        if (resp.ok) {
            toast('已删除', 'success');
            for (const skillName in skillChatState) {
                if (skillChatState[skillName].conversationId === conversationId) {
                    skillChatState[skillName] = { conversationId: null, transcript: [], conversationMessages: [], stream: null, taskIssueStates: {} };
                    if (selectedSkill && selectedSkill.name === skillName) {
                        renderTranscript(skillName);
                    }
                    break;
                }
            }
        }
    } catch (e) {
        toast('删除失败: ' + e.message, 'error');
    }
}

// ===== Sidebar Toggle (button now lives in the footer) =====
document.getElementById('sidebarToggle').addEventListener('click', () => {
    const sidebar = document.getElementById('sidebar');
    sidebar.classList.toggle('collapsed');
    const btn = document.getElementById('sidebarToggle');
    btn.textContent = sidebar.classList.contains('collapsed') ? '›' : '‹';
});

// ===== Robot Working Animation =====
function showRobotWorking() {
    hideRobotWorking();
    const welcome = document.getElementById('chatWelcome');
    if (welcome) welcome.style.display = 'none';
    const messages = document.getElementById('chatMessages');
    const el = document.createElement('div');
    el.className = 'robot-working';
    el.id = 'robotWorking';
    el.innerHTML =
        '<div class="robot-icon">🤖</div>' +
        '<div class="robot-dots"><span></span><span></span><span></span></div>' +
        '<span class="robot-text">正在思考...</span>';
    messages.appendChild(el);
    scrollChat();
}

function hideRobotWorking() {
    const el = document.getElementById('robotWorking');
    if (el) el.remove();
}

// ===== Send Button & Enter =====
const sendBtn = document.getElementById('runBtn');
const msgInput = document.getElementById('messageInput');

function updateSendButtonState() {
    const hasMessage = msgInput.value.trim().length > 0;
    const formFields = document.getElementById('inputForm').querySelectorAll('input, select, textarea');
    const hasFormContent = Array.from(formFields).some(f => f.value.trim().length > 0);
    sendBtn.disabled = !hasMessage && !hasFormContent;
}

sendBtn.addEventListener('click', runSkill);

msgInput.addEventListener('keydown', (e) => {
    if (e.isComposing || e.keyCode === 229) return;
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        runSkill();
    }
});
msgInput.addEventListener('input', () => {
    msgInput.style.height = 'auto';
    msgInput.style.height = Math.min(msgInput.scrollHeight, 120) + 'px';
    updateSendButtonState();
});
document.getElementById('inputForm').addEventListener('input', updateSendButtonState);
document.getElementById('inputForm').addEventListener('change', updateSendButtonState);

// ===== Per-message action buttons (复制 / 建议方案 / 创建 Issue) =====
// Delegated click handler on the chat area — handles dynamically rendered buttons.
document.getElementById('chatMessages').addEventListener('click', async function(ev) {
    var btn = ev.target.closest('[data-action]');
    if (!btn || btn.disabled) return;
    var action = btn.getAttribute('data-action');
    var taskId = btn.getAttribute('data-task-id');
    var skillName = btn.getAttribute('data-skill-name') || activeSkillName;
    if (!taskId || !skillName) return;

    if (action === 'copy') {
        // Copy the content of this specific message
        var msgEl = btn.closest('.msg');
        var contentEl = msgEl ? msgEl.querySelector('.msg-content') : null;
        var text = contentEl ? (contentEl.innerText || contentEl.textContent || '') : '';
        if (!text) return;
        try {
            await navigator.clipboard.writeText(text);
            var orig = btn.textContent;
            btn.textContent = '✓ 已复制';
            btn.classList.add('success');
            setTimeout(function() { btn.textContent = orig; btn.classList.remove('success'); }, 2000);
        } catch (e) {
            appendTranscript(skillName, { type: 'error', content: '复制失败，请手动选择文本复制', timestamp: Date.now() });
            renderTranscript(skillName);
        }
        return;
    }

    if (action === 'propose-solution') {
        await perMessageActionCall(btn, skillName, taskId, BASE + '/runs/' + encodeURIComponent(taskId) + '/solution', null, 'propose');
        return;
    }

    if (action === 'create-issue') {
        // Creating an issue requires a solution first; auto-propose if not yet done.
        // The button stays disabled with "处理中..." text across both calls.
        btn.disabled = true;
        btn.textContent = '处理中...';
        var issueState = getTaskIssueState(skillName, taskId);
        if (!issueState.solutionProposed) {
            var proposeOk = await perMessageActionCall(btn, skillName, taskId, BASE + '/runs/' + encodeURIComponent(taskId) + '/solution', null, 'propose-auto');
            if (!proposeOk) {
                // perMessageActionCall already restored the button on error
                return;
            }
        }
        await perMessageActionCall(btn, skillName, taskId, BASE + '/runs/' + encodeURIComponent(taskId) + '/issue', '{}', 'issue');
        return;
    }
});

async function perMessageActionCall(btn, skillName, taskId, url, body, mode) {
    var origText = btn.textContent;
    // Only flip to "处理中..." if not already in that state (create-issue sets it before the auto-propose call)
    if (btn.textContent !== '处理中...') {
        btn.disabled = true;
        btn.textContent = '处理中...';
    }
    try {
        var resp = await fetch(url, { method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: body });
        var data = await resp.json();
        if (!resp.ok) {
            btn.textContent = '失败';
            btn.classList.add('error');
            appendTranscript(skillName, { type: 'error', content: '操作失败: ' + (data.error || data.message || resp.status), timestamp: Date.now() });
            renderTranscript(skillName);
            setTimeout(function() { btn.disabled = false; btn.textContent = origText; btn.classList.remove('error'); }, 2500);
            return false;
        }
        var issueState = getTaskIssueState(skillName, taskId);

        // Build result text for chat
        var resultParts = [];

        if (mode === 'propose' || mode === 'propose-auto') {
            issueState.solutionProposed = true;
            if (mode === 'propose') {
                // Standalone propose-solution click: show success on the button
                btn.textContent = '✓ 方案已生成';
                btn.classList.add('success');
                btn.disabled = true;
            }
            // For propose-auto, leave the button as "处理中..." — the subsequent issue call will update it.
            resultParts.push('**建议方案：**');
            if (data.solution && data.solution.options) {
                (data.solution.options || []).forEach(function(opt, i) {
                    resultParts.push((i + 1) + '. **' + (opt.title || '') + '** — ' + (opt.description || '') + ' (工作量: ' + (opt.effort || '') + ')');
                });
                if (data.solution.recommendedOptionId) resultParts.push('推荐: ' + data.solution.recommendedOptionId);
                if (data.solution.rationale) resultParts.push('理由: ' + data.solution.rationale);
            }
            if (data.rootCause) resultParts.push('**根因：** ' + data.rootCause);
            if (data.status) resultParts.push('**状态：** ' + data.status);
            // For propose-auto, no separate chat message and no re-render — the issue call follows immediately
            // and will use the same button reference (renderTranscript here would detach it).
            if (mode === 'propose' && resultParts.length > 1) {
                appendTranscript(skillName, { type: 'response', content: resultParts.join('\n'), timestamp: Date.now(), taskId: taskId });
                renderTranscript(skillName);
            }
        }

        if (mode === 'issue') {
            if (data.issueId) {
                issueState.issueId = data.issueId;
                issueState.issueStatus = data.status || null;
            }
            btn.textContent = '✓ Issue 已创建';
            btn.classList.add('success');
            btn.disabled = true;
            resultParts = [];
            if (data.issueId) {
                resultParts.push('**Issue 已创建**');
                resultParts.push('Issue ID: `' + data.issueId + '`');
                if (data.externalIssueId) resultParts.push('外部 Issue: ' + data.externalIssueId);
            }
            if (data.status) resultParts.push('状态: ' + data.status);
            if (data.rootCause) resultParts.push('根因: ' + data.rootCause);
            if (resultParts.length > 0) {
                appendTranscript(skillName, { type: 'response', content: resultParts.join('\n'), timestamp: Date.now(), taskId: taskId });
            }
            // Re-render so the parent message's action buttons / badges reflect new state
            // (button becomes "✓ Issue (...)" badge, 建议方案 button is hidden)
            renderTranscript(skillName);
        }

        return true;
    } catch (e) {
        btn.textContent = '错误';
        btn.classList.add('error');
        setTimeout(function() { btn.disabled = false; btn.textContent = origText; btn.classList.remove('error'); }, 2500);
        appendTranscript(skillName, { type: 'error', content: '请求异常: ' + e.message, timestamp: Date.now() });
        renderTranscript(skillName);
        return false;
    }
}

// ===== Reconnect to running tasks after page refresh =====
async function reconnectRunningTasks() {
    try {
        const resp = await fetch(`${BASE}/runs?status=RUNNING`, { headers: authHeaders() });
        if (handleAuthError(resp)) return;
        const data = await resp.json();
        const tasks = data.tasks || [];
        if (tasks.length === 0) return;
        console.log('[reconnect] Found', tasks.length, 'running task(s)');
        for (const t of tasks) {
            // Load the latest conversation to recover user messages that
            // are NOT replayed by the SSE stream endpoint (SSE only replays
            // agent events: thought, tool_call, tool_result, done).
            // To avoid duplicates, keep entries up to and including the
            // last 'user' message — SSE will rebuild everything after it.
            const conv = await loadLatestConversation(t.skillId);
            if (conv && conv.messages && conv.messages.length > 0) {
                const fullTranscript = conv.messages.map(m => ({
                    type: roleToType(m.role),
                    content: m.content,
                    timestamp: m.timestamp || Date.now(),
                    taskId: m.taskId || null
                }));
                var lastUserIdx = -1;
                for (var i = 0; i < fullTranscript.length; i++) {
                    if (fullTranscript[i].type === 'user') lastUserIdx = i;
                }
                var kept = lastUserIdx >= 0 ? fullTranscript.slice(0, lastUserIdx + 1) : [];
                skillChatState[t.skillId] = {
                    conversationId: conv.conversationId,
                    transcript: kept,
                    conversationMessages: transcriptToLlmHistory(kept),
                    stream: null,
                    taskIssueStates: {}
                };
            } else {
                getSkillState(t.skillId);
            }
            subscribeStream(t.taskId, t.skillId);
        }
    } catch (e) {
        console.error('[reconnect] Failed to check running tasks:', e);
    }
}

// ===== Feature Modals (Tools, Workflows, Cost, Issues, Patrol, Alerts) =====

function openFeatureModal(title, bodyHtml) {
    var existing = document.getElementById('featureModal');
    if (existing) existing.remove();
    var modal = document.createElement('div');
    modal.className = 'history-modal';
    modal.id = 'featureModal';
    modal.innerHTML =
        '<div class="feature-modal-card">' +
            '<div class="history-modal-header">' +
                '<span class="history-modal-title">' + escapeHtml(title) + '</span>' +
                '<button class="history-modal-close" id="featureModalClose">✕</button>' +
            '</div>' +
            '<div class="history-modal-body">' + bodyHtml + '</div>' +
        '</div>';
    document.body.appendChild(modal);
    modal.addEventListener('click', function(e) {
        if (e.target === modal || e.target.id === 'featureModalClose') modal.remove();
    });
    return modal;
}

function featureEmpty(msg) {
    return '<div class="feature-empty">' + escapeHtml(msg || '暂无数据') + '</div>';
}

// --- Tools & Plugins ---
async function showToolsModal() {
    var modal = openFeatureModal('工具 & 插件', '<div class="feature-empty">加载中...</div>');
    var body = modal.querySelector('.history-modal-body');
    try {
        var toolsResp = await fetch(BASE + '/tools', { headers: authHeaders() });
        var toolsData = await toolsResp.json();
        var pluginsResp = await fetch(BASE + '/tools/plugins', { headers: authHeaders() });
        var pluginsData = await pluginsResp.json();
        var tools = toolsData.tools || [];
        var plugins = pluginsData || [];

        var html = '<div style="margin-bottom:16px;padding:10px;background:var(--bg-card);border-radius:8px;border:1px solid var(--border);font-size:12px;color:var(--text-secondary);">' +
            '<strong>💡 工具发现机制：</strong> ' + escapeHtml(toolsData.discoveryHint || 'Tool providers auto-discovered as Spring @Component beans') +
            '</div>';

        html += '<div style="margin-bottom:20px;">';
        html += '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">已注册工具 (' + tools.length + ')</div>';
        if (tools.length === 0) {
            html += featureEmpty('无可用工具');
        } else {
            html += '<table class="feature-table"><thead><tr><th>工具名</th><th>描述</th><th>操作</th></tr></thead><tbody>';
            tools.forEach(function(t, idx) {
                var desc = (t.description || '').substring(0, 80);
                html += '<tr>' +
                    '<td><code style="color:var(--accent)">' + escapeHtml(t.name) + '</code></td>' +
                    '<td style="font-size:12px;color:var(--text-secondary);">' + escapeHtml(desc) + '</td>' +
                    '<td><button class="feature-action-btn" data-tool-idx="' + idx + '">查看详情</button></td>' +
                    '</tr>';
            });
            html += '</tbody></table>';
        }
        html += '</div>';

        html += '<div>';
        html += '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">工具插件 (' + plugins.length + ')</div>';
        if (plugins.length === 0) {
            html += featureEmpty('无已注册插件');
        } else {
            html += '<table class="feature-table"><thead><tr><th>名称</th><th>版本</th><th>工具</th><th>描述</th></tr></thead><tbody>';
            plugins.forEach(function(p) {
                html += '<tr><td>' + escapeHtml(p.name) + '</td><td>' + escapeHtml(p.version || '') + '</td><td>' +
                    escapeHtml((p.toolNames || []).join(', ')) + '</td><td>' + escapeHtml(p.description || '') + '</td></tr>';
            });
            html += '</tbody></table>';
        }
        html += '</div>';
        body.innerHTML = html;

        // Attach detail expand handlers
        body.querySelectorAll('[data-tool-idx]').forEach(function(btn) {
            btn.addEventListener('click', function() {
                var idx = parseInt(btn.dataset.toolIdx, 10);
                var tool = tools[idx];
                if (!tool) return;
                var row = btn.closest('tr');
                var existing = row.nextElementSibling;
                if (existing && existing.className === 'tool-detail-row') {
                    existing.remove();
                    btn.textContent = '查看详情';
                    return;
                }
                var params = tool.parameters || {};
                var paramHtml;
                if (params && params.properties) {
                    var props = params.properties;
                    var required = params.required || [];
                    var rows = Object.keys(props).map(function(k) {
                        var p = props[k] || {};
                        var req = required.indexOf(k) >= 0 ? '<span class="feature-badge red">必填</span>' : '<span class="feature-badge">可选</span>';
                        return '<tr><td><code>' + escapeHtml(k) + '</code></td><td>' + escapeHtml(p.type || '') + '</td><td>' +
                            escapeHtml(p.description || '') + '</td><td>' + req + '</td></tr>';
                    }).join('');
                    paramHtml = '<table class="feature-table" style="margin-top:6px;"><thead><tr><th>参数</th><th>类型</th><th>描述</th><th>必填</th></tr></thead><tbody>' +
                        rows + '</tbody></table>';
                } else {
                    paramHtml = '<pre style="font-size:11px;color:var(--text-muted);white-space:pre-wrap;background:var(--bg-card);padding:8px;border-radius:6px;">' +
                        escapeHtml(JSON.stringify(params, null, 2)) + '</pre>';
                }
                var detailHtml = '<tr class="tool-detail-row"><td colspan="3">' +
                    '<div style="padding:10px;background:var(--bg-card);border-radius:8px;">' +
                    '<div style="margin-bottom:6px;font-size:12px;color:var(--text-secondary);"><strong>完整描述：</strong> ' + escapeHtml(tool.description || '(无描述)') + '</div>' +
                    '<div style="margin-bottom:6px;font-size:12px;color:var(--text-secondary);"><strong>参数 Schema：</strong></div>' +
                    paramHtml +
                    '</div></td></tr>';
                row.insertAdjacentHTML('afterend', detailHtml);
                btn.textContent = '收起';
            });
        });
    } catch (e) {
        body.innerHTML = featureEmpty('加载失败: ' + e.message);
    }
}

// --- Workflows ---
function extractTriggerVars(steps) {
    var vars = [];
    var seen = {};
    var pattern = /\$\{trigger\.(\w+)\}/g;
    steps.forEach(function(step) {
        var inputs = step.inputs || {};
        for (var key in inputs) {
            var val = String(inputs[key] || '');
            var m;
            while ((m = pattern.exec(val)) !== null) {
                if (!seen[m[1]]) { seen[m[1]] = true; vars.push(m[1]); }
            }
        }
        var cond = String(step.condition || '');
        var m2;
        pattern.lastIndex = 0;
        while ((m2 = pattern.exec(cond)) !== null) {
            if (!seen[m2[1]]) { seen[m2[1]] = true; vars.push(m2[1]); }
        }
    });
    return vars;
}

function renderWorkflowResult(runData) {
    var html = '<div style="margin-top:10px;padding-top:10px;border-top:1px dashed var(--border);">';
    var statusColor = runData.success ? 'var(--green)' : 'var(--red)';
    html += '<div style="display:flex;align-items:center;gap:12px;margin-bottom:8px;">';
    html += '<span style="font-size:12px;font-weight:600;color:' + statusColor + ';">状态: ' + escapeHtml(runData.status || (runData.success ? 'COMPLETED' : 'FAILED')) + '</span>';
    if (runData.durationMs) {
        html += '<span style="font-size:11px;color:var(--text-secondary);">耗时: ' + (runData.durationMs / 1000).toFixed(1) + 's</span>';
    }
    if (runData.failedStep) {
        html += '<span style="font-size:11px;color:var(--red);">失败步骤: ' + escapeHtml(runData.failedStep) + '</span>';
    }
    if (runData.errorMessage) {
        html += '<span style="font-size:11px;color:var(--red);">' + escapeHtml(runData.errorMessage) + '</span>';
    }
    html += '</div>';
    var stepResults = runData.stepResults || {};
    for (var stepName in stepResults) {
        var sr = stepResults[stepName];
        var stStatus = sr.status || 'SKIPPED';
        var stColor = stStatus === 'SUCCEEDED' ? 'var(--green)' : (stStatus === 'FAILED' ? 'var(--red)' : 'var(--text-secondary)');
        var stBg = stStatus === 'SUCCEEDED' ? 'var(--green-light)' : (stStatus === 'FAILED' ? 'var(--red-light)' : 'var(--bg-hover)');
        html += '<div style="margin-bottom:8px;padding:8px;background:' + stBg + ';border-radius:6px;border:1px solid var(--border);">';
        html += '<div style="display:flex;justify-content:space-between;align-items:center;">';
        html += '<span style="font-size:12px;font-weight:600;">' + escapeHtml(stepName) + '</span>';
        html += '<span style="font-size:11px;font-weight:600;color:' + stColor + ';">' + escapeHtml(stStatus) + '</span>';
        html += '</div>';
        if (sr.taskId) {
            html += '<div style="font-size:10px;color:var(--text-secondary);margin-top:2px;">Task: ' + escapeHtml(sr.taskId) + '</div>';
        }
        if (sr.report) {
            var preview = sr.report.length > 500 ? sr.report.substring(0, 500) + '...' : sr.report;
            html += '<details style="margin-top:6px;"><summary style="font-size:11px;color:var(--text-link);cursor:pointer;">报告 (' + sr.report.length + ' 字符)</summary>';
            html += '<div style="margin-top:4px;font-size:11px;color:var(--text-primary);white-space:pre-wrap;max-height:400px;overflow-y:auto;padding:6px;background:var(--bg-input);border-radius:4px;">' + escapeHtml(sr.report) + '</div>';
            html += '</details>';
        }
        html += '</div>';
    }
    html += '</div>';
    return html;
}

async function showWorkflowsModal() {
    var modal = openFeatureModal('工作流', '<div class="feature-empty">加载中...</div>');
    var body = modal.querySelector('.history-modal-body');
    try {
        var resp = await fetch(BASE + '/workflows', { headers: authHeaders() });
        var data = await resp.json();
        var workflows = Array.isArray(data) ? data : (data.workflows || []);
        // Fetch detail for each workflow to get steps
        var workflowsWithSteps = [];
        for (var i = 0; i < workflows.length; i++) {
            var wf = workflows[i];
            try {
                var detailResp = await fetch(BASE + '/workflows/' + encodeURIComponent(wf.name), { headers: authHeaders() });
                if (detailResp.ok) {
                    var detail = await detailResp.json();
                    if (detail.steps) wf.steps = detail.steps;
                }
            } catch (e) { /* use list data */ }
            workflowsWithSteps.push(wf);
        }
        workflows = workflowsWithSteps;
        if (workflows.length === 0) {
            body.innerHTML = featureEmpty('无已注册工作流');
            return;
        }

        var html = '<table class="feature-table"><thead><tr><th>名称</th><th>描述</th><th>步骤数</th><th>操作</th></tr></thead><tbody>';
        workflows.forEach(function(wf) {
            var steps = wf.steps || [];
            html += '<tr><td><strong>' + escapeHtml(wf.name) + '</strong></td><td>' + escapeHtml(wf.description || '') + '</td><td>' + steps.length + '</td>' +
                '<td><button class="feature-action-btn" data-workflow="' + escapeHtml(wf.name) + '">运行</button></td></tr>';
        });
        html += '</tbody></table>';

        // Steps detail + trigger inputs + result placeholder
        workflows.forEach(function(wf) {
            var steps = wf.steps || [];
            var triggerVars = extractTriggerVars(steps);
            html += '<div id="wf-card-' + wf.name.replace(/[^a-zA-Z0-9]/g, '') + '" style="margin-top:16px;padding:12px;background:var(--bg-card);border-radius:8px;border:1px solid var(--border);">';
            html += '<div style="font-weight:600;margin-bottom:8px;">' + escapeHtml(wf.name) + ' — 步骤</div>';
            steps.forEach(function(step, idx) {
                var condHtml = step.condition ? ' <span class="feature-badge orange">条件: ' + escapeHtml(step.condition) + '</span>' : '';
                var failHtml = step.onFailure ? ' <span class="feature-badge red">失败: ' + escapeHtml(step.onFailure) + '</span>' : '';
                html += '<div style="padding:4px 0;font-size:12px;color:var(--text-secondary);">' +
                    (idx+1) + '. <strong>' + escapeHtml(step.skill) + '</strong>' + condHtml + failHtml + '</div>';
            });
            // Trigger inputs
            if (triggerVars.length > 0) {
                html += '<div style="margin-top:8px;padding-top:8px;border-top:1px dashed var(--border);">';
                html += '<div style="font-size:12px;font-weight:600;margin-bottom:6px;">触发参数:</div>';
                triggerVars.forEach(function(v) {
                    html += '<div style="margin-bottom:4px;display:flex;align-items:center;gap:8px;">';
                    html += '<label style="font-size:12px;width:100px;flex-shrink:0;">' + escapeHtml(v) + '</label>';
                    html += '<input type="text" data-wf-trigger="' + escapeHtml(wf.name) + '" data-var="' + escapeHtml(v) + '" placeholder="输入 ' + escapeHtml(v) + '" style="flex:1;padding:4px 8px;font-size:12px;border-radius:4px;border:1px solid var(--border);background:var(--bg-input);"></div>';
                });
                html += '</div>';
            }
            // Result placeholder
            html += '<div data-wf-result="' + escapeHtml(wf.name) + '"></div>';
            html += '</div>';
        });

        body.innerHTML = html;

        // Attach run buttons
        body.querySelectorAll('[data-workflow]').forEach(function(btn) {
            btn.addEventListener('click', async function() {
                var wfName = btn.dataset.workflow;
                btn.disabled = true;
                btn.textContent = '运行中...';

                // Build trigger inputs from fields
                var triggerInputs = {};
                body.querySelectorAll('[data-wf-trigger="' + wfName + '"]').forEach(function(el) {
                    var varName = el.dataset.var;
                    if (el.value) triggerInputs[varName] = el.value;
                });

                var resultDiv = body.querySelector('[data-wf-result="' + wfName + '"]');
                if (resultDiv) resultDiv.innerHTML = '<div style="margin-top:8px;font-size:12px;color:var(--text-secondary);">运行中，请稍候...</div>';

                try {
                    var runResp = await fetch(BASE + '/workflows/' + encodeURIComponent(wfName) + '/run', {
                        method: 'POST',
                        headers: authHeaders({ 'Content-Type': 'application/json' }),
                        body: JSON.stringify(triggerInputs)
                    });
                    var runData = await runResp.json();

                    // Render results
                    if (resultDiv) {
                        resultDiv.innerHTML = renderWorkflowResult(runData);
                    }

                    // Button state
                    if (runData.success) {
                        btn.textContent = '成功';
                        btn.style.background = 'var(--green-light)';
                        btn.style.borderColor = 'var(--green)';
                        btn.style.color = 'var(--green)';
                    } else {
                        btn.textContent = '失败';
                        btn.style.background = 'var(--red-light)';
                        btn.style.borderColor = 'var(--red)';
                        btn.style.color = 'var(--red)';
                    }
                    setTimeout(function() {
                        btn.disabled = false;
                        btn.textContent = '运行';
                        btn.style.cssText = '';
                    }, 3000);
                } catch (e) {
                    if (resultDiv) {
                        resultDiv.innerHTML = '<div style="margin-top:8px;padding:8px;color:var(--red);font-size:12px;">运行出错: ' + escapeHtml(e.message) + '</div>';
                    }
                    btn.textContent = '错误';
                    setTimeout(function() {
                        btn.disabled = false;
                        btn.textContent = '运行';
                        btn.style.cssText = '';
                    }, 3000);
                }
            });
        });
    } catch (e) {
        body.innerHTML = featureEmpty('加载失败: ' + e.message);
    }
}

// --- Cost Dashboard ---
async function showCostModal() {
    var modal = openFeatureModal('成本看板', '<div class="feature-empty">加载中...</div>');
    var body = modal.querySelector('.history-modal-body');
    try {
        var now = Date.now();
        var from = Math.floor((now - 7 * 24 * 3600 * 1000) / 1000);
        var to = Math.floor(now / 1000);
        // Note: backend expects epoch millis for CostStore queries
        var fromMs = from * 1000;
        var toMs = to * 1000;
        var resp = await fetch(BASE + '/cost/summary?from=' + fromMs + '&to=' + toMs, { headers: authHeaders() });
        if (!resp.ok) {
            body.innerHTML = featureEmpty('成本核算未启用或请求失败 (' + resp.status + ')');
            return;
        }
        var data = await resp.json();
        var html = '<div class="feature-stat-row">';
        var totalCost = parseFloat(data.totalCost || 0);
        var totalInputTokens = parseInt(data.totalInputTokens || 0);
        var totalOutputTokens = parseInt(data.totalOutputTokens || 0);
        var totalReqs = parseInt(data.requestCount || 0);
        html += '<div class="feature-stat"><div class="feature-stat-value">' + totalCost.toFixed(4) + '</div><div class="feature-stat-label">总成本</div></div>';
        html += '<div class="feature-stat"><div class="feature-stat-value">' + totalInputTokens + '</div><div class="feature-stat-label">输入 Tokens</div></div>';
        html += '<div class="feature-stat"><div class="feature-stat-value">' + totalOutputTokens + '</div><div class="feature-stat-label">输出 Tokens</div></div>';
        html += '<div class="feature-stat"><div class="feature-stat-value">' + totalReqs + '</div><div class="feature-stat-label">请求数</div></div>';
        html += '</div>';

        var util = data.budget ? ((parseFloat(data.utilization || 0)) * 100).toFixed(1) + '%' : '—';
        html += '<div style="margin:8px 0;font-size:12px;color:var(--text-secondary);">维度: ' + escapeHtml(data.dimension || 'global') +
            ' | 预算: ' + (data.budget ? '¥' + parseFloat(data.budget).toFixed(2) : '无') +
            ' | 利用率: ' + util + '</div>';

        // Records section
        html += '<div style="margin-top:16px;">';
        html += '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">' +
            '<span style="font-size:13px;font-weight:600;color:var(--text-secondary);">成本记录</span>' +
            '<button class="feature-action-btn" id="costRefreshBtn">刷新</button></div>';
        html += '<div id="costRecordsContainer"><div class="feature-empty">加载中...</div></div>';
        html += '</div>';

        body.innerHTML = html;

        async function loadRecords() {
            var container = body.querySelector('#costRecordsContainer');
            container.innerHTML = '<div class="feature-empty">加载中...</div>';
            try {
                var recResp = await fetch(BASE + '/cost/records?from=' + fromMs + '&to=' + toMs, { headers: authHeaders() });
                if (!recResp.ok) {
                    container.innerHTML = featureEmpty('获取记录失败 (' + recResp.status + ')');
                    return;
                }
                var recData = await recResp.json();
                var records = recData.records || [];
                if (records.length === 0) {
                    container.innerHTML = featureEmpty('暂无成本记录。运行一次 skill 后再查看。');
                    return;
                }
                var rHtml = '<table class="feature-table"><thead><tr><th>时间</th><th>用户</th><th>Skill</th><th>模型</th><th>输入</th><th>输出</th><th>缓存</th><th>费用</th><th>任务 ID</th></tr></thead><tbody>';
                records.forEach(function(r) {
                    var date = new Date(parseInt(r.timestamp));
                    var ts = date.toLocaleString();
                    rHtml += '<tr>' +
                        '<td style="font-size:11px;">' + escapeHtml(ts) + '</td>' +
                        '<td>' + escapeHtml(r.userId || '') + '</td>' +
                        '<td>' + escapeHtml(r.skillName || '') + '</td>' +
                        '<td style="font-size:11px;">' + escapeHtml(r.model || '') + '</td>' +
                        '<td>' + (r.inputTokens || 0) + '</td>' +
                        '<td>' + (r.outputTokens || 0) + '</td>' +
                        '<td>' + (r.cacheReadTokens || 0) + '</td>' +
                        '<td><strong>¥' + (parseFloat(r.cost || 0)).toFixed(6) + '</strong></td>' +
                        '<td><code style="font-size:10px;">' + escapeHtml(r.taskId || '') + '</code></td>' +
                        '</tr>';
                });
                rHtml += '</tbody></table>';
                container.innerHTML = rHtml;
            } catch (e) {
                container.innerHTML = featureEmpty('加载记录失败: ' + e.message);
            }
        }
        await loadRecords();
        body.querySelector('#costRefreshBtn').addEventListener('click', loadRecords);
    } catch (e) {
        body.innerHTML = featureEmpty('加载失败: ' + e.message);
    }
}

// --- Issues (Problem Closure) ---

// Build action buttons for an issue based on its status (used in 已记录问题 list)
function buildIssueActions(issue) {
    if (!issue) return '';
    var s = issue.status;
    var issueId = escapeHtml(issue.issueId || '');
    var taskId = escapeHtml(issue.taskId || '');
    if (s === 'CLOSED') {
        return '<span style="font-size:11px;color:var(--green);">✓ 已关闭</span>';
    }
    var acts = '';
    // SOLUTION_PROPOSED → 创建外部 Issue (advance to FIX_IN_PROGRESS)
    if (s === 'SOLUTION_PROPOSED' || s === 'DIAGNOSED') {
        acts += '<button class="feature-action-btn" data-action="create-external" data-task-id="' + taskId + '" data-issue-id="' + issueId + '">创建外部 Issue</button> ';
    }
    // FIX_IN_PROGRESS → 验证修复 (advance to VERIFIED)
    if (s === 'FIX_IN_PROGRESS') {
        acts += '<button class="feature-action-btn" data-action="verify" data-task-id="' + taskId + '" data-issue-id="' + issueId + '">验证修复</button> ';
    }
    // VERIFIED → 关闭问题 (advance to CLOSED)
    if (s === 'VERIFIED') {
        acts += '<button class="feature-action-btn" data-action="close" data-task-id="' + taskId + '" data-issue-id="' + issueId + '">关闭问题</button>';
    }
    return acts;
}

// Build detail panel HTML for an issue (shown when row is clicked)
function buildIssueDetail(run) {
    var i = run.issue || {};
    var html = '<div style="padding:12px;background:var(--bg-card);border-radius:8px;font-size:12px;line-height:1.7;">';
    if (run.skillId) {
        html += '<div><strong>Skill:</strong> ' + escapeHtml(run.skillId) + '</div>';
    }
    if (i.userId) {
        html += '<div><strong>创建人:</strong> ' + escapeHtml(i.userId) + '</div>';
    }
    if (i.userQuery) {
        html += '<div style="margin-top:6px;"><strong>用户问题:</strong> ' + escapeHtml(i.userQuery) + '</div>';
    }
    if (i.rootCause) {
        html += '<div style="margin-top:6px;"><strong>根因:</strong> ' + escapeHtml(i.rootCause) + '</div>';
    }
    if (i.solution && i.solution.options) {
        html += '<div style="margin-top:6px;"><strong>建议方案:</strong></div>';
        (i.solution.options || []).forEach(function(opt, idx) {
            html += '<div style="margin-left:12px;">' + (idx + 1) + '. <strong>' + escapeHtml(opt.title || '') + '</strong> — ' + escapeHtml(opt.description || '');
            html += ' <span class="feature-badge orange">工作量: ' + escapeHtml(opt.effort || '') + '</span>';
            if (opt.temporary) html += ' <span class="feature-badge red">临时</span>';
            html += '</div>';
        });
        if (i.solution.recommendedOptionId) {
            html += '<div style="margin-left:12px;"><em>推荐: ' + escapeHtml(i.solution.recommendedOptionId) + '</em></div>';
        }
        if (i.solution.rationale) {
            html += '<div style="margin-left:12px;"><strong>理由:</strong> ' + escapeHtml(i.solution.rationale) + '</div>';
        }
    }
    if (i.verificationResult) {
        html += '<div style="margin-top:6px;"><strong>验证结果:</strong> ' + (i.verificationResult.passed ? '✓ 通过' : '✗ 未通过') + '</div>';
        if (i.verificationResult.summary) {
            html += '<div style="margin-left:12px;">' + escapeHtml(i.verificationResult.summary) + '</div>';
        }
    }
    if (i.fixCommitId) {
        html += '<div style="margin-top:6px;"><strong>修复 Commit:</strong> <code>' + escapeHtml(i.fixCommitId) + '</code></div>';
    }
    if (i.knowledgeEntryId) {
        html += '<div><strong>知识库条目:</strong> <code>' + escapeHtml(i.knowledgeEntryId) + '</code></div>';
    }
    html += '<div style="margin-top:6px;color:var(--text-muted);font-size:11px;">';
    html += '创建: ' + (i.createdAt ? new Date(i.createdAt).toLocaleString() : '') + ' | ';
    html += '更新: ' + (i.updatedAt ? new Date(i.updatedAt).toLocaleString() : '');
    html += '</div>';
    html += '</div>';
    return html;
}

async function showIssuesModal() {
    var modal = openFeatureModal('问题闭环', '<div class="feature-empty">加载中...</div>');
    var body = modal.querySelector('.history-modal-body');
    try {
        // Single fetch: backend returns terminal runs pre-joined with issue status
        var resp = await fetch(BASE + '/issues/recent-runs?limit=20', { headers: authHeaders() });
        if (!resp.ok) {
            if (resp.status === 503) {
                body.innerHTML = featureEmpty('问题闭环功能未启用 (snap-agent.issue-closure.enabled=false)');
            } else {
                body.innerHTML = featureEmpty('加载失败: HTTP ' + resp.status);
            }
            return;
        }
        var data = await resp.json();
        var runs = data.runs || [];

        var html = '<div style="padding:8px 0;font-size:13px;color:var(--text-secondary);margin-bottom:12px;line-height:1.6;">' +
            '问题闭环流程: <strong>创建 Issue</strong> (最近运行) → ' +
            '<strong>创建外部 Issue</strong> → <strong>验证修复</strong> → <strong>关闭问题</strong> (已记录问题)。<br>' +
            '点击已记录问题的行可展开查看详情。</div>';

        // Split: issues list (runs with issues) + pending runs (runs without issues)
        var issuesList = runs.filter(function(r) { return r.issue != null; });
        var pendingRuns = runs.filter(function(r) { return r.issue == null; });

        // === 已记录问题 ===
        if (issuesList.length > 0) {
            html += '<div style="margin-bottom:16px;">';
            html += '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">已记录问题 (' + issuesList.length + ')</div>';
            html += '<table class="feature-table"><thead><tr><th>Issue / 任务</th><th>状态</th><th>根因摘要</th><th>更新时间</th><th>操作</th></tr></thead><tbody>';
            issuesList.forEach(function(r) {
                var i = r.issue;
                // Ensure taskId is available on issue object for buildIssueActions
                if (!i.taskId) i.taskId = r.taskId;
                var statusBadge = (i.status === 'CLOSED' || i.status === 'VERIFIED') ? 'green' : 'orange';
                var rootSummary = (i.rootCause || '').substring(0, 60);
                var updated = i.updatedAt ? new Date(i.updatedAt).toLocaleString() : '';
                html += '<tr class="issue-row" data-issue-id="' + escapeHtml(i.issueId || '') + '" data-task-id="' + escapeHtml(r.taskId || '') + '" style="cursor:pointer;">' +
                    '<td><code>' + escapeHtml(i.issueId || '') + '</code><br><code style="font-size:10px;color:var(--text-secondary);">' + escapeHtml(r.taskId || '') + '</code></td>' +
                    '<td><span class="feature-badge ' + statusBadge + '">' + escapeHtml(i.status || '') + '</span></td>' +
                    '<td style="font-size:12px;">' + escapeHtml(rootSummary) + '</td>' +
                    '<td style="font-size:11px;">' + escapeHtml(updated) + '</td>' +
                    '<td>' + buildIssueActions(i) + '</td>' +
                    '</tr>';
                // Hidden detail row — toggled by clicking the row
                html += '<tr class="issue-detail-row" style="display:none;"><td colspan="5">' + buildIssueDetail(r) + '</td></tr>';
            });
            html += '</tbody></table></div>';
        }

        // === 最近运行 (only pending — tasks without issues) ===
        html += '<div>';
        html += '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">最近运行 (' + pendingRuns.length + ')</div>';
        if (pendingRuns.length === 0) {
            html += featureEmpty('暂无待发起 Issue 的运行记录');
        } else {
            html += '<table class="feature-table"><thead><tr><th>任务 ID</th><th>Skill</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody>';
            pendingRuns.forEach(function(r) {
                var created = r.createdAt ? new Date(r.createdAt).toLocaleString() : '';
                html += '<tr data-task-id="' + escapeHtml(r.taskId || '') + '">' +
                    '<td><code style="font-size:10px;">' + escapeHtml(r.taskId || '') + '</code></td>' +
                    '<td>' + escapeHtml(r.skillId || r.skill || '') + '</td>' +
                    '<td><span class="feature-badge">' + escapeHtml(r.status || '') + '</span></td>' +
                    '<td style="font-size:11px;">' + escapeHtml(created) + '</td>' +
                    '<td><button class="feature-action-btn" data-action="create-issue" data-task-id="' + escapeHtml(r.taskId || '') + '">创建 Issue</button></td>' +
                    '</tr>';
            });
            html += '</tbody></table>';
        }
        html += '</div>';
        body.innerHTML = html;

        // Row click handler — toggle detail panel (only in 已记录问题 list)
        body.addEventListener('click', function(ev) {
            // Don't toggle when clicking a button or inside the detail row
            if (ev.target.closest('[data-action]') || ev.target.closest('.issue-detail-row')) return;
            var row = ev.target.closest('tr.issue-row');
            if (!row) return;
            var detailRow = row.nextElementSibling;
            if (detailRow && detailRow.classList.contains('issue-detail-row')) {
                var isHidden = detailRow.style.display === 'none';
                detailRow.style.display = isHidden ? '' : 'none';
            }
        });

        // Action button handler (delegated)
        body.addEventListener('click', async function(ev) {
            var btn = ev.target.closest('[data-action]');
            if (!btn || btn.disabled) return;
            var action = btn.dataset.action;
            var taskId = btn.dataset.taskId;
            var issueId = btn.dataset.issueId;
            var origText = btn.textContent;
            btn.disabled = true;
            btn.textContent = '处理中...';
            try {
                var url, method = 'POST', reqBody = null;
                if (action === 'create-issue') {
                    // Full flow: auto-propose solution then create external issue
                    var proposeResp = await fetch(BASE + '/runs/' + encodeURIComponent(taskId) + '/solution', { method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }) });
                    var proposeData = await proposeResp.json();
                    if (!proposeResp.ok) {
                        btn.textContent = '失败';
                        btn.style.background = 'var(--red-light)';
                        alert('建议方案生成失败: ' + (proposeData.error || proposeData.message || proposeResp.status));
                        setTimeout(function() { btn.disabled = false; btn.textContent = origText; btn.style.cssText = ''; }, 2500);
                        return;
                    }
                    // Now create external issue
                    url = BASE + '/runs/' + encodeURIComponent(taskId) + '/issue';
                    reqBody = '{}';
                } else if (action === 'create-external') {
                    url = BASE + '/runs/' + encodeURIComponent(taskId) + '/issue';
                    reqBody = '{}';
                } else if (action === 'verify') {
                    url = BASE + '/issues/' + encodeURIComponent(issueId) + '/verify';
                } else if (action === 'close') {
                    url = BASE + '/issues/' + encodeURIComponent(issueId) + '/close';
                }
                var headers = authHeaders({ 'Content-Type': 'application/json' });
                var resp2 = await fetch(url, { method: method, headers: headers, body: reqBody });
                var data2 = await resp2.json();
                if (!resp2.ok) {
                    btn.textContent = '失败';
                    btn.style.background = 'var(--red-light)';
                    alert('操作失败: ' + (data2.error || data2.message || resp2.status));
                    setTimeout(function() { btn.disabled = false; btn.textContent = origText; btn.style.cssText = ''; }, 2500);
                    return;
                }
                btn.textContent = '✓ 成功';
                btn.style.background = 'var(--green-light)';
                btn.style.borderColor = 'var(--green)';
                btn.style.color = 'var(--green)';

                // For create-issue action: the task moves from 最近运行 to 已记录问题.
                // Simplest UX: refresh the modal so the task disappears from pending list
                // and appears in the issues list.
                if (action === 'create-issue') {
                    // Show a brief inline result, then refresh after 1.5s
                    var pendingRow = btn.closest('tr');
                    if (pendingRow) {
                        var cells = pendingRow.querySelectorAll('td');
                        if (cells.length >= 5) {
                            cells[4].innerHTML = '<span style="color:var(--green);font-size:11px;">✓ Issue ' + escapeHtml(data2.issueId || '') + ' 已创建</span>';
                        }
                    }
                    setTimeout(function() { showIssuesModal(); }, 1500);
                    return;
                }

                // For create-external/verify/close: update the issue row in-place
                var issueRow = btn.closest('tr.issue-row');
                if (issueRow) {
                    var newStatus = data2.status;
                    var newIssueId = data2.issueId || issueId;
                    // Update status badge (2nd column) and action buttons (5th column)
                    var rowCells = issueRow.querySelectorAll('td');
                    if (rowCells.length >= 5) {
                        var badgeColor = (newStatus === 'CLOSED' || newStatus === 'VERIFIED') ? 'green' : 'orange';
                        rowCells[1].innerHTML = '<span class="feature-badge ' + badgeColor + '">' + escapeHtml(newStatus || '') + '</span>';
                        // Rebuild action buttons (5th column)
                        rowCells[4].innerHTML = buildIssueActions({ status: newStatus, issueId: newIssueId, taskId: taskId });
                    }
                    // Refresh the detail panel (if visible)
                    var detailRow = issueRow.nextElementSibling;
                    if (detailRow && detailRow.classList.contains('issue-detail-row') && detailRow.style.display !== 'none') {
                        // Build a synthetic run object for buildIssueDetail
                        var syntheticRun = { taskId: taskId, skillId: null, issue: data2 };
                        detailRow.innerHTML = '<td colspan="5">' + buildIssueDetail(syntheticRun) + '</td>';
                    } else if (detailRow && detailRow.classList.contains('issue-detail-row')) {
                        // Update hidden detail too, so it's fresh when expanded
                        var syntheticRun2 = { taskId: taskId, skillId: null, issue: data2 };
                        detailRow.innerHTML = '<td colspan="5">' + buildIssueDetail(syntheticRun2) + '</td>';
                    }
                }
            } catch (e) {
                btn.textContent = '错误';
                btn.style.background = 'var(--red-light)';
                setTimeout(function() { btn.disabled = false; btn.textContent = origText; btn.style.cssText = ''; }, 2500);
                alert('请求异常: ' + e.message);
            }
        });
    } catch (e) {
        body.innerHTML = featureEmpty('加载失败: ' + e.message);
    }
}

// --- Patrol Tasks ---
async function showPatrolModal() {
    var modal = openFeatureModal('巡检任务', '<div class="feature-empty">加载中...</div>');
    var body = modal.querySelector('.history-modal-body');
    try {
        var tasksResp = await fetch(BASE + '/patrol/tasks', { headers: authHeaders() });
        var tasksData = await tasksResp.json();
        var reportsResp = await fetch(BASE + '/patrol/reports', { headers: authHeaders() });
        var reportsData = await reportsResp.json();
        var tasks = Array.isArray(tasksData) ? tasksData : (tasksData.tasks || []);
        var reports = reportsData.reports || [];

        // Build a skill-name dropdown using loaded skills (skillsData)
        var skillOptions = '';
        if (skillsData && skillsData.length) {
            skillsData.forEach(function(s) {
                skillOptions += '<option value="' + escapeHtml(s.name) + '">' + escapeHtml(s.name) + ' — ' + escapeHtml((s.description || '').substring(0, 60)) + '</option>';
            });
        }

        var html = '<div style="margin-bottom:20px;">';
        html += '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">' +
            '<span style="font-size:13px;font-weight:600;color:var(--text-secondary);">巡检任务 (' + tasks.length + ')</span>' +
            '<button class="feature-action-btn" id="createPatrolBtn">新建巡检</button></div>';
        // Create form (hidden by default)
        html += '<div id="patrolCreateForm" style="display:none;padding:12px;background:var(--bg-card);border-radius:8px;border:1px solid var(--border);margin-bottom:12px;">';
        html += '<div style="font-weight:600;margin-bottom:8px;">新建巡检任务</div>';
        html += '<div style="display:flex;flex-direction:column;gap:8px;">';
        html += '<div><label style="font-size:12px;color:var(--text-secondary);">巡检名称</label>' +
            '<input type="text" id="patrolNameInput" placeholder="例如: SKU详情表数据巡检" ' +
            'style="width:100%;padding:6px 10px;background:var(--bg-input);border:1px solid var(--border);border-radius:4px;color:var(--text-primary);font-size:13px;">' +
            '<div style="font-size:11px;color:var(--text-muted);margin-top:2px;">给人看的名字, 方便在告警中快速识别来源。</div></div>';
        html += '<div><label style="font-size:12px;color:var(--text-secondary);">Skill</label>' +
            '<select id="patrolSkillSelect" style="width:100%;padding:6px 10px;background:var(--bg-input);border:1px solid var(--border);border-radius:4px;color:var(--text-primary);font-size:13px;">' +
            skillOptions + '</select></div>';
        html += '<div><label style="font-size:12px;color:var(--text-secondary);">Cron 表达式</label>' +
            '<input type="text" id="patrolCronInput" placeholder="0 0/15 * * * ? (每15分钟)" ' +
            'style="width:100%;padding:6px 10px;background:var(--bg-input);border:1px solid var(--border);border-radius:4px;color:var(--text-primary);font-size:13px;"></div>';
        // Dynamic input area: text mode (for skills without inputs) or JSON mode (for skills with inputs)
        html += '<div id="patrolTextInputWrap" style="display:none;">' +
            '<label style="font-size:12px;color:var(--text-secondary);">自然语言指令</label>' +
            '<textarea id="patrolTextInput" placeholder="例如: 检查drp_allocation_plan表是否有generate_date等于当前日期的记录" rows="3" ' +
            'style="width:100%;padding:6px 10px;background:var(--bg-input);border:1px solid var(--border);border-radius:4px;color:var(--text-primary);font-size:13px;"></textarea>' +
            '<div style="font-size:11px;color:var(--text-muted);margin-top:2px;">该 Skill 无预定义参数, 直接输入自然语言指令即可 (等价于在聊天框输入)。</div></div>';
        html += '<div id="patrolJsonInputWrap">' +
            '<label style="font-size:12px;color:var(--text-secondary);">输入参数 (JSON)</label>' +
            '<textarea id="patrolInputsInput" placeholder="{}" rows="3" ' +
            'style="width:100%;padding:6px 10px;background:var(--bg-input);border:1px solid var(--border);border-radius:4px;color:var(--text-primary);font-size:12px;font-family:monospace;"></textarea></div>';
        html += '<div><label style="font-size:12px;color:var(--text-secondary);">告警关键词 (可选, 逗号分隔)</label>' +
            '<input type="text" id="patrolAlertKeywordsInput" placeholder="例如: 未生成, 0行, 无记录, 失败" ' +
            'style="width:100%;padding:6px 10px;background:var(--bg-input);border:1px solid var(--border);border-radius:4px;color:var(--text-primary);font-size:13px;">' +
            '<div style="font-size:11px;color:var(--text-muted);margin-top:2px;">巡检报告中出现这些词时触发告警推送; 留空则仅用内置关键词 (异常/错误/失败/critical/warning 等)。</div></div>';
        html += '<div style="display:flex;gap:8px;">' +
            '<button class="feature-action-btn" id="patrolSubmitBtn">创建</button>' +
            '<button class="feature-action-btn" id="patrolCancelBtn" style="background:var(--bg-input);">取消</button></div>';
        html += '<div id="patrolCreateStatus" style="font-size:11px;color:var(--text-muted);margin-top:4px;"></div>';
        html += '</div></div>';

        if (tasks.length === 0) {
            html += featureEmpty('无巡检任务');
        } else {
            html += '<table class="feature-table"><thead><tr><th>名称</th><th>Skill</th><th>Cron</th><th>告警关键词</th><th>状态</th><th>操作</th></tr></thead><tbody>';
            tasks.forEach(function(t) {
                var taskId = escapeHtml(t.taskId || t.id);
                var taskName = escapeHtml(t.name || t.taskName || taskId);
                var isActive = t.active || t.enabled;
                var badge = isActive ? 'green' : 'orange';
                html += '<tr><td><strong>' + taskName + '</strong><br><span style="font-size:10px;color:var(--text-muted);">' + taskId + '</span></td><td>' + escapeHtml(t.skillName || t.skillId || t.skill) + '</td>' +
                    '<td><code>' + escapeHtml(t.cron || '') + '</code></td>' +
                    '<td style="font-size:11px;">' + escapeHtml(t.alertKeywords || '—') + '</td>' +
                    '<td><span class="feature-badge ' + badge + '">' + (isActive ? '运行中' : '已停止') + '</span></td>' +
                    '<td><button class="feature-action-btn" data-patrol-toggle="' + taskId + '" style="margin-right:4px;">' + (isActive ? '禁用' : '启用') + '</button>' +
                    '<button class="feature-action-btn" data-patrol-id="' + taskId + '" style="background:var(--red-light);border-color:var(--red);color:var(--red);">删除</button></td></tr>';
            });
            html += '</tbody></table>';
        }
        html += '</div>';

        html += '<div>';
        html += '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">巡检报告 (' + reports.length + ')</div>';
        if (reports.length === 0) {
            html += featureEmpty('无巡检报告');
        } else {
            // Build a map from patrolId → task name for display
            var taskNameMap = {};
            tasks.forEach(function(t) {
                var tid = t.taskId || t.id;
                if (tid) taskNameMap[tid] = t.name || t.taskName || tid;
            });
            reports.forEach(function(r, idx) {
                var isAnomaly = r.anomalyDetected === true;
                var badge = isAnomaly ? 'red' : 'green';
                var label = isAnomaly ? '告警' : '正常';
                var timeStr = r.triggeredAt ? new Date(r.triggeredAt).toLocaleString('zh-CN', {hour12:false}) : '—';
                var summary = r.summary || '';
                var summaryPreview = summary.length > 100 ? summary.substring(0, 100) + '...' : summary;
                var patrolId = r.patrolId || r.id || '';
                var patrolName = taskNameMap[patrolId] || patrolId;
                html += '<div style="margin-bottom:8px;padding:10px;background:var(--bg-card);border-radius:8px;border:1px solid var(--border);' + (isAnomaly ? 'border-left:3px solid var(--red);' : '') + '">';
                html += '<div style="display:flex;justify-content:space-between;align-items:center;">';
                html += '<div style="display:flex;align-items:center;gap:8px;">';
                html += '<span class="feature-badge ' + badge + '">' + label + '</span>';
                html += '<span style="font-size:11px;font-weight:600;color:var(--text-primary);">' + escapeHtml(patrolName) + '</span>';
                html += '<span style="font-size:11px;color:var(--text-secondary);">' + escapeHtml(r.skillName || '') + '</span>';
                html += '<span style="font-size:11px;color:var(--text-muted);">' + timeStr + '</span>';
                html += '</div>';
                html += '<span style="font-size:10px;color:var(--text-muted);font-family:monospace;">' + escapeHtml(patrolId) + '</span>';
                html += '</div>';
                html += '<details style="margin-top:6px;"><summary style="font-size:12px;color:var(--text-link);cursor:pointer;">' + escapeHtml(summaryPreview) + '</summary>';
                html += '<div style="margin-top:6px;font-size:11px;color:var(--text-primary);white-space:pre-wrap;max-height:400px;overflow-y:auto;padding:8px;background:var(--bg-input);border-radius:4px;">' + escapeHtml(summary) + '</div>';
                html += '</details>';
                html += '</div>';
            });
        }
        html += '</div>';
        body.innerHTML = html;

        // Attach toggle and delete buttons
        body.querySelectorAll('[data-patrol-toggle]').forEach(function(btn) {
            btn.addEventListener('click', async function() {
                var id = btn.dataset.patrolToggle;
                btn.disabled = true;
                try {
                    var resp = await fetch(BASE + '/patrol/tasks/' + encodeURIComponent(id) + '/toggle', {
                        method: 'PATCH', headers: authHeaders()
                    });
                    if (resp.ok) { showPatrolModal(); }
                } catch (e) { /* ignore */ }
                btn.disabled = false;
            });
        });
        body.querySelectorAll('[data-patrol-id]').forEach(function(btn) {
            btn.addEventListener('click', async function() {
                var id = btn.dataset.patrolId;
                if (!confirm('删除巡检任务 ' + id + '?')) return;
                try {
                    var resp = await fetch(BASE + '/patrol/tasks/' + encodeURIComponent(id), {
                        method: 'DELETE', headers: authHeaders()
                    });
                    if (resp.ok) { showPatrolModal(); }
                } catch (e) { /* ignore */ }
            });
        });

        // Attach create button toggle
        var createBtn = body.querySelector('#createPatrolBtn');
        var createForm = body.querySelector('#patrolCreateForm');
        var submitBtn = body.querySelector('#patrolSubmitBtn');
        var cancelBtn = body.querySelector('#patrolCancelBtn');
        var skillSelect = body.querySelector('#patrolSkillSelect');
        var cronInput = body.querySelector('#patrolCronInput');
        var nameInput = body.querySelector('#patrolNameInput');
        var textInput = body.querySelector('#patrolTextInput');
        var jsonInput = body.querySelector('#patrolInputsInput');
        var textWrap = body.querySelector('#patrolTextInputWrap');
        var jsonWrap = body.querySelector('#patrolJsonInputWrap');
        var alertKwInput = body.querySelector('#patrolAlertKeywordsInput');
        var statusDiv = body.querySelector('#patrolCreateStatus');

        function toggleInputMode() {
            var skillName = skillSelect.value;
            var skill = (skillsData || []).find(function(s) { return s.name === skillName; });
            var hasInputs = skill && skill.inputs && skill.inputs.length > 0;
            if (hasInputs) {
                textWrap.style.display = 'none';
                jsonWrap.style.display = 'block';
            } else {
                textWrap.style.display = 'block';
                jsonWrap.style.display = 'none';
            }
        }
        skillSelect.addEventListener('change', toggleInputMode);
        toggleInputMode(); // initialize for the default selection

        createBtn.addEventListener('click', function() {
            if (createForm.style.display === 'none') {
                createForm.style.display = 'block';
                createBtn.textContent = '收起';
            } else {
                createForm.style.display = 'none';
                createBtn.textContent = '新建巡检';
            }
        });
        cancelBtn.addEventListener('click', function() {
            createForm.style.display = 'none';
            createBtn.textContent = '新建巡检';
            statusDiv.textContent = '';
        });
        submitBtn.addEventListener('click', async function() {
            var skillName = skillSelect.value;
            var cron = cronInput.value.trim();
            var patrolName = nameInput.value.trim();
            var alertKeywords = alertKwInput.value.trim();
            if (!skillName) { statusDiv.textContent = '请选择 Skill'; return; }
            if (!cron) { statusDiv.textContent = '请输入 Cron 表达式'; return; }
            if (!patrolName) { statusDiv.textContent = '请输入巡检名称'; return; }

            // Build inputs: text mode wraps as _user_message, JSON mode parses directly
            var inputsObj;
            var textMode = textWrap.style.display !== 'none';
            if (textMode) {
                var text = textInput.value.trim();
                if (!text) { statusDiv.textContent = '请输入自然语言指令'; return; }
                inputsObj = { _user_message: text };
            } else {
                var inputsText = jsonInput.value.trim() || '{}';
                try { inputsObj = JSON.parse(inputsText); }
                catch (e) { statusDiv.textContent = '输入参数不是合法 JSON'; return; }
            }

            submitBtn.disabled = true;
            submitBtn.textContent = '创建中...';
            statusDiv.textContent = '';
            try {
                var resp = await fetch(BASE + '/patrol/tasks', {
                    method: 'POST',
                    headers: authHeaders({ 'Content-Type': 'application/json' }),
                    body: JSON.stringify({ name: patrolName, skillName: skillName, cron: cron, inputs: inputsObj, alertKeywords: alertKeywords || null })
                });
                var data = await resp.json();
                if (!resp.ok) {
                    statusDiv.textContent = '创建失败: ' + (data.error || resp.status);
                    submitBtn.disabled = false;
                    submitBtn.textContent = '创建';
                    return;
                }
                statusDiv.style.color = 'var(--green)';
                statusDiv.textContent = '✓ 创建成功, ID: ' + (data.id || '');
                submitBtn.textContent = '✓';
                setTimeout(function() { showPatrolModal(); }, 800);
            } catch (e) {
                statusDiv.textContent = '请求异常: ' + e.message;
                submitBtn.disabled = false;
                submitBtn.textContent = '创建';
            }
        });

        // Attach delete handlers
        body.querySelectorAll('[data-patrol-id]').forEach(function(btn) {
            btn.addEventListener('click', async function() {
                if (!confirm('删除该巡检任务?')) return;
                var id = btn.dataset.patrolId;
                btn.disabled = true;
                btn.textContent = '删除中...';
                try {
                    await fetch(BASE + '/patrol/tasks/' + encodeURIComponent(id), {
                        method: 'DELETE',
                        headers: authHeaders()
                    });
                    btn.textContent = '已删除';
                    setTimeout(function() { showPatrolModal(); }, 600);
                } catch (e) {
                    btn.textContent = '失败';
                    setTimeout(function() { btn.disabled = false; btn.textContent = '删除'; }, 1500);
                }
            });
        });
    } catch (e) {
        body.innerHTML = featureEmpty('巡检功能未启用或加载失败: ' + e.message);
    }
}

// --- Alerts ---
async function showAlertsModal() {
    var modal = openFeatureModal('告警', '<div class="feature-empty">加载中...</div>');
    var body = modal.querySelector('.history-modal-body');
    try {
        var resp = await fetch(BASE + '/alerts', { headers: authHeaders() });
        var data = await resp.json();
        var alerts = data.alerts || [];
        if (alerts.length === 0) {
            body.innerHTML = featureEmpty('无活跃告警');
            return;
        }
        var html = '';
        alerts.forEach(function(a) {
            var isActive = (a.status || 'ACTIVE') === 'ACTIVE';
            var badgeColor = isActive ? 'red' : 'green';
            var statusLabel = isActive ? '告警中' : '已解决';
            var firstTime = a.firstSeen ? new Date(a.firstSeen).toLocaleString('zh-CN', {hour12:false}) : '—';
            var lastTime = a.lastSeen ? new Date(a.lastSeen).toLocaleString('zh-CN', {hour12:false}) : '—';
            var count = a.count || 1;
            var isContinuous = count > 1;
            var msg = a.firstMessage || a.message || '';
            var msgPreview = msg.length > 120 ? msg.substring(0, 120) + '...' : msg;
            var alertTitle = a.source || a.type || '未知告警';
            var typeLabel = a.type === 'patrol' ? '巡检告警' : (a.type || '告警');

            html += '<div style="margin-bottom:12px;padding:12px;background:var(--bg-card);border-radius:8px;border:1px solid var(--border);' + (isActive ? 'border-left:3px solid var(--red);' : '') + '">';
            // Header row — title + status badge
            html += '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">';
            html += '<div style="display:flex;align-items:center;gap:8px;">';
            html += '<span class="feature-badge ' + badgeColor + '">' + statusLabel + '</span>';
            html += '<span style="font-size:13px;font-weight:600;color:var(--text-primary);">' + escapeHtml(alertTitle) + '</span>';
            html += '<span style="font-size:11px;color:var(--text-muted);">' + escapeHtml(typeLabel) + '</span>';
            html += '</div>';
            html += '<span style="font-size:10px;color:var(--text-muted);font-family:monospace;">' + escapeHtml(a.id || '') + '</span>';
            html += '</div>';

            // Stats row
            html += '<div style="display:flex;gap:16px;flex-wrap:wrap;font-size:11px;color:var(--text-secondary);margin-bottom:8px;">';
            html += '<span>告警次数: <strong style="color:' + (isContinuous ? 'var(--red)' : 'var(--text-primary)') + ';">' + count + '</strong>' + (isContinuous ? ' (连续告警)' : '') + '</span>';
            html += '<span>首次: ' + firstTime + '</span>';
            html += '<span>最近: ' + lastTime + '</span>';
            html += '</div>';

            // Message (expandable)
            if (msg) {
                html += '<details style="margin-bottom:8px;"><summary style="font-size:12px;color:var(--text-link);cursor:pointer;">' + escapeHtml(msgPreview) + '</summary>';
                html += '<div style="margin-top:6px;font-size:11px;color:var(--text-primary);white-space:pre-wrap;max-height:300px;overflow-y:auto;padding:8px;background:var(--bg-input);border-radius:4px;">' + escapeHtml(msg) + '</div>';
                html += '</details>';
            }

            // Footer
            html += '<div style="display:flex;justify-content:space-between;align-items:center;">';
            html += '<span style="font-size:11px;color:var(--text-muted);">推送通道: ' + (isContinuous ? 'webhook/email (若已配置)' : '未触发推送') + '</span>';
            if (isActive) {
                html += '<button class="feature-action-btn" data-alert-id="' + escapeHtml(a.id) + '">标记已解决</button>';
            } else {
                html += '<span style="font-size:11px;color:var(--green);">已解决</span>';
            }
            html += '</div>';
            html += '</div>';
        });
        body.innerHTML = html;

        body.querySelectorAll('[data-alert-id]').forEach(function(btn) {
            btn.addEventListener('click', async function() {
                var alertId = btn.dataset.alertId;
                btn.disabled = true;
                btn.textContent = '处理中...';
                try {
                    await fetch(BASE + '/alerts/' + encodeURIComponent(alertId) + '/resolve', {
                        method: 'POST',
                        headers: authHeaders()
                    });
                    showAlertsModal();
                } catch (e) {
                    btn.textContent = '失败';
                    btn.disabled = false;
                }
            });
        });
    } catch (e) {
        body.innerHTML = featureEmpty('告警功能未启用或加载失败: ' + e.message);
    }
}

// --- Feature nav button listeners ---
document.getElementById('navToolsBtn').addEventListener('click', showToolsModal);
document.getElementById('navWorkflowsBtn').addEventListener('click', showWorkflowsModal);
document.getElementById('navCostBtn').addEventListener('click', showCostModal);
document.getElementById('navIssuesBtn').addEventListener('click', showIssuesModal);
document.getElementById('navPatrolBtn').addEventListener('click', showPatrolModal);
document.getElementById('navAlertsBtn').addEventListener('click', showAlertsModal);
document.getElementById('navKnowledgeBtn').addEventListener('click', showKnowledgeModal);

// --- Alert badge polling ---
async function refreshAlertBadge() {
    var badge = document.getElementById('alertBadge');
    if (!badge) return;
    try {
        var resp = await fetch(BASE + '/alerts?page=0&size=1', { headers: authHeaders() });
        if (!resp.ok) { badge.style.display = 'none'; return; }
        var data = await resp.json();
        var activeCount = 0;
        var alerts = data.alerts || [];
        alerts.forEach(function(a) {
            if ((a.status || 'ACTIVE') === 'ACTIVE') activeCount++;
        });
        // The API returns paginated; use total as a rough indicator for active count
        // when the page only fetched 1 item. For accuracy, check if the fetched item is active.
        // We use total as the badge number since all alerts (active + recently resolved) matter.
        var total = data.total || 0;
        if (total > 0) {
            badge.textContent = total > 99 ? '99+' : total;
            badge.style.display = 'inline-block';
        } else {
            badge.style.display = 'none';
        }
    } catch (e) {
        badge.style.display = 'none';
    }
}
refreshAlertBadge();
setInterval(refreshAlertBadge, 30000);

// --- Knowledge Base ---
async function showKnowledgeModal() {
    var modal = openFeatureModal('知识库', '<div class="feature-empty">加载中...</div>');
    var body = modal.querySelector('.history-modal-body');
    try {
        var resp = await fetch(BASE + '/knowledge/status', { headers: authHeaders() });
        if (!resp.ok) {
            body.innerHTML = featureEmpty('知识库未启用 (HTTP ' + resp.status + ')');
            return;
        }
        var data = await resp.json();

        // Status stats — the "知识片段" card is clickable to expand the full fragment list
        var html = '<div class="feature-stat-row">';
        html += '<div class="feature-stat" id="knowledgeStatFragments" style="cursor:pointer;transition:background 0.15s;" title="点击查看所有知识点">';
        html += '<div class="feature-stat-value">' + data.fragmentCount + '</div><div class="feature-stat-label">知识片段 (点击展开)</div></div>';
        html += '<div class="feature-stat"><div class="feature-stat-value">' + data.maxFragments + '</div><div class="feature-stat-label">注入上限</div></div>';
        html += '<div class="feature-stat"><div class="feature-stat-value">' + data.minScore + '</div><div class="feature-stat-label">最低分数</div></div>';
        html += '</div>';
        // Fragment list container — populated lazily on stat-card click
        html += '<div id="knowledgeFragmentsList" style="display:none;margin:12px 0;"></div>';

        // Action bar: reload + upload
        html += '<div style="display:flex;gap:8px;align-items:center;margin:12px 0;padding:8px;background:var(--bg-card);border-radius:8px;border:1px solid var(--border);">';
        html += '<button class="feature-action-btn" id="knowledgeReloadBtn">🔄 刷新知识库</button>';
        html += '<label class="feature-action-btn" style="cursor:pointer;display:inline-block;">' +
            '<span>📤 上传 Markdown</span>' +
            '<input type="file" id="knowledgeUploadInput" accept=".md" hidden style="display:none;">' +
            '</label>';
        html += '<span id="knowledgeActionStatus" style="font-size:11px;color:var(--text-muted);"></span>';
        html += '</div>';

        // Sources
        var sources = data.sources || [];
        html += '<div style="margin:12px 0;"><div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">数据源 (' + sources.length + ')</div>';
        html += '<table class="feature-table"><thead><tr><th>类型</th><th>路径</th><th>可写</th></tr></thead><tbody>';
        sources.forEach(function(s) {
            html += '<tr><td>' + escapeHtml(s.type) + '</td><td><code>' + escapeHtml(s.dir) + '</code></td>' +
                '<td>' + (s.writable ? '<span class="feature-badge green">是</span>' : '<span class="feature-badge">否</span>') + '</td></tr>';
        });
        html += '</tbody></table></div>';

        // Search box
        html += '<div style="margin:16px 0;">';
        html += '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">检索测试</div>';
        html += '<div style="display:flex;gap:8px;margin-bottom:12px;">';
        html += '<input type="text" id="knowledgeSearchInput" placeholder="输入关键词搜索知识片段..." ' +
            'style="flex:1;padding:8px 12px;background:var(--bg-input);border:1px solid var(--border);border-radius:var(--radius-sm);color:var(--text-primary);font-size:13px;">';
        html += '<button class="feature-action-btn" id="knowledgeSearchBtn">搜索</button>';
        html += '</div>';
        html += '<div id="knowledgeSearchResults"></div>';
        html += '</div>';

        body.innerHTML = html;

        // Attach reload + upload handlers
        var actionStatus = body.querySelector('#knowledgeActionStatus');
        body.querySelector('#knowledgeReloadBtn').addEventListener('click', async function() {
            var btn = body.querySelector('#knowledgeReloadBtn');
            btn.disabled = true;
            btn.textContent = '刷新中...';
            actionStatus.textContent = '';
            try {
                var rResp = await fetch(BASE + '/knowledge/reload', { method: 'POST', headers: authHeaders() });
                var rData = await rResp.json();
                if (!rResp.ok) {
                    actionStatus.style.color = 'var(--red)';
                    actionStatus.textContent = '失败: ' + (rData.error || rResp.status);
                    btn.disabled = false;
                    btn.textContent = '🔄 刷新知识库';
                    return;
                }
                actionStatus.style.color = 'var(--green)';
                actionStatus.textContent = '✓ 已刷新，当前 ' + rData.fragmentCount + ' 个片段';
                btn.disabled = false;
                btn.textContent = '🔄 刷新知识库';
                // Refresh the status stats at top of modal
                setTimeout(showKnowledgeModal, 1000);
            } catch (e) {
                actionStatus.style.color = 'var(--red)';
                actionStatus.textContent = '异常: ' + e.message;
                btn.disabled = false;
                btn.textContent = '🔄 刷新知识库';
            }
        });

        body.querySelector('#knowledgeUploadInput').addEventListener('change', async function(e) {
            var file = e.target.files && e.target.files[0];
            if (!file) return;
            actionStatus.textContent = '上传 ' + file.name + ' 中...';
            actionStatus.style.color = 'var(--text-muted)';
            try {
                var formData = new FormData();
                formData.append('file', file);
                var uResp = await fetch(BASE + '/knowledge/upload', {
                    method: 'POST',
                    headers: authHeaders(),
                    body: formData
                });
                var uData = await uResp.json();
                if (!uResp.ok) {
                    actionStatus.style.color = 'var(--red)';
                    actionStatus.textContent = '失败: ' + (uData.error || uResp.status);
                    return;
                }
                actionStatus.style.color = 'var(--green)';
                actionStatus.textContent = '✓ 已上传 ' + file.name + '，当前 ' + uData.fragmentCount + ' 个片段';
                // Refresh the modal to show new fragment count + sources
                setTimeout(showKnowledgeModal, 1000);
            } catch (err) {
                actionStatus.style.color = 'var(--red)';
                actionStatus.textContent = '异常: ' + err.message;
            }
        });

        // Attach search handler
        var searchInput = document.getElementById('knowledgeSearchInput');
        var searchBtn = document.getElementById('knowledgeSearchBtn');
        var resultsDiv = document.getElementById('knowledgeSearchResults');

        // Stat-card click → toggle fragment list
        var fragmentsListDiv = body.querySelector('#knowledgeFragmentsList');
        var fragmentsLoaded = false;
        body.querySelector('#knowledgeStatFragments').addEventListener('click', async function() {
            if (fragmentsListDiv.style.display === 'none') {
                fragmentsListDiv.style.display = 'block';
                if (!fragmentsLoaded) {
                    await loadAllFragments(fragmentsListDiv);
                    fragmentsLoaded = true;
                }
            } else {
                fragmentsListDiv.style.display = 'none';
            }
        });

        async function loadAllFragments(container) {
            container.innerHTML = '<div class="feature-empty">加载知识点列表中...</div>';
            try {
                var fResp = await fetch(BASE + '/knowledge/fragments', { headers: authHeaders() });
                if (!fResp.ok) {
                    container.innerHTML = featureEmpty('加载失败 (HTTP ' + fResp.status + ')');
                    return;
                }
                var fData = await fResp.json();
                var fragments = fData.fragments || [];
                if (fragments.length === 0) {
                    container.innerHTML = featureEmpty('知识库为空');
                    return;
                }
                var fHtml = '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">' +
                    '全部知识点 (' + fragments.length + ') — 点击展开/折叠内容</div>';
                fragments.forEach(function(f, idx) {
                    var safeId = 'knowledgeFragment_' + idx;
                    fHtml += '<div style="margin-bottom:8px;padding:10px;background:var(--bg-card);border-radius:var(--radius-sm);border:1px solid var(--border);cursor:pointer;" ' +
                        'data-fragment-idx="' + idx + '" id="' + safeId + '_header">' +
                        '<div style="display:flex;justify-content:space-between;align-items:center;">' +
                        '<span style="font-weight:600;color:var(--accent);">' + escapeHtml(f.title || '(无标题)') + '</span>' +
                        '<span style="font-size:11px;color:var(--text-muted);">' + escapeHtml(f.source || '') + '</span>' +
                        '</div></div>';
                    fHtml += '<div id="' + safeId + '_body" style="display:none;margin:-6px 0 8px 0;padding:10px;background:var(--bg);border-radius:var(--radius-sm);border:1px solid var(--border);">' +
                        '<pre style="font-size:12px;color:var(--text-primary);white-space:pre-wrap;word-break:break-word;max-height:400px;overflow-y:auto;line-height:1.5;">' + escapeHtml(f.content || '') + '</pre>' +
                        '</div>';
                });
                container.innerHTML = fHtml;
                // Attach click handlers to each fragment header
                container.querySelectorAll('[data-fragment-idx]').forEach(function(header) {
                    header.addEventListener('click', function() {
                        var idxAttr = header.getAttribute('data-fragment-idx');
                        var bodyEl = container.querySelector('#knowledgeFragment_' + idxAttr + '_body');
                        if (bodyEl) {
                            bodyEl.style.display = bodyEl.style.display === 'none' ? 'block' : 'none';
                        }
                    });
                });
            } catch (e) {
                container.innerHTML = featureEmpty('加载失败: ' + e.message);
            }
        }

        async function doSearch() {
            var q = searchInput.value.trim();
            if (!q) { resultsDiv.innerHTML = ''; return; }
            resultsDiv.innerHTML = '<div class="feature-empty">搜索中...</div>';
            try {
                var sResp = await fetch(BASE + '/knowledge/search?q=' + encodeURIComponent(q), { headers: authHeaders() });
                var sData = await sResp.json();
                var fragments = sData.fragments || [];
                if (fragments.length === 0) {
                    resultsDiv.innerHTML = featureEmpty('无匹配的知识片段');
                    return;
                }
                var fHtml = '';
                fragments.forEach(function(f) {
                    var scoreText = f.score != null ? (Math.round(f.score * 100) + '%') : 'N/A';
                    fHtml += '<div style="margin-bottom:12px;padding:12px;background:var(--bg-card);border-radius:var(--radius-sm);border:1px solid var(--border);">' +
                        '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px;">' +
                        '<span style="font-weight:600;color:var(--accent);">' + escapeHtml(f.title) + '</span>' +
                        '<span class="feature-badge" style="background:var(--accent);color:#fff;font-size:11px;padding:2px 8px;border-radius:10px;">相关度 ' + scoreText + '</span>' +
                        '</div>' +
                        '<div style="font-size:11px;color:var(--text-muted);margin-bottom:8px;">来源: ' + escapeHtml(f.source) + '</div>' +
                        '<pre style="font-size:12px;color:var(--text-primary);white-space:pre-wrap;word-break:break-word;max-height:200px;overflow-y:auto;line-height:1.5;">' + escapeHtml(f.content) + '</pre>' +
                        '</div>';
                });
                resultsDiv.innerHTML = fHtml;
            } catch (e) {
                resultsDiv.innerHTML = featureEmpty('搜索失败: ' + e.message);
            }
        }

        searchBtn.addEventListener('click', doSearch);
        searchInput.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') doSearch();
        });
    } catch (e) {
        body.innerHTML = featureEmpty('加载失败: ' + e.message);
    }
}

// ===== Init =====
(async function() {
    await loadAuthConfig();
    const ok = await checkUserStatus();
    if (ok) {
        await loadSkills();
        loadModels();
        reconnectRunningTasks();
    }
})();
