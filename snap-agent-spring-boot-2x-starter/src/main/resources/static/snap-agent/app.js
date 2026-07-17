// SnapAgent SPA — per-skill independent chat sessions with parallel streams
// Version: v23 (feat: enable all v0.3-v1.0 features, add feature nav bar with modals)
console.log('[SnapAgent] app.js v23 loaded');

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
            stream: null
        };
    }
    return skillChatState[skillName];
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
                timestamp: m.timestamp || Date.now()
            }));
            skillChatState[skillName] = {
                conversationId: conv.conversationId,
                transcript: transcript,
                conversationMessages: transcriptToLlmHistory(transcript),
                stream: null
            };
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
        const el = renderMessageEl(entry);
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
function renderMessageEl(entry) {
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
    return el;
}

// ===== Append a transcript entry; render to live DOM only if the skill is active =====
function appendTranscript(skillName, entry) {
    const state = getSkillState(skillName);
    state.transcript.push(entry);
    if (activeSkillName === skillName) {
        hideRobotWorking();
        document.getElementById('chatMessages').appendChild(renderMessageEl(entry));
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
            if (activeSkillName === skillName) updateSendButtonState();
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
                    timestamp: t.timestamp || Date.now()
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
                timestamp: m.timestamp
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
        timestamp: m.timestamp || Date.now()
    }));
    skillChatState[skillId] = {
        conversationId: conv.conversationId,
        transcript: transcript,
        conversationMessages: transcriptToLlmHistory(transcript),
        stream: null
    };
    const skillLi = document.querySelector(`.skill-list li[data-skill-id="${CSS.escape(skillId)}"]`);
    selectSkill(skill, skillLi);
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
                    skillChatState[skillName] = { conversationId: null, transcript: [], conversationMessages: [], stream: null };
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
                    timestamp: m.timestamp || Date.now()
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
                    stream: null
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

        var html = '<div style="margin-bottom:20px;">';
        html += '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">已注册工具 (' + tools.length + ')</div>';
        if (tools.length === 0) {
            html += featureEmpty('无可用工具');
        } else {
            html += '<table class="feature-table"><thead><tr><th>工具名</th></tr></thead><tbody>';
            tools.forEach(function(t) {
                html += '<tr><td><code style="color:var(--accent)">' + escapeHtml(t.name) + '</code></td></tr>';
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
    } catch (e) {
        body.innerHTML = featureEmpty('加载失败: ' + e.message);
    }
}

// --- Workflows ---
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

        // Steps detail
        html += '<div style="margin-top:16px;">';
        workflows.forEach(function(wf) {
            var steps = wf.steps || [];
            html += '<div style="margin-bottom:12px;padding:12px;background:var(--bg-card);border-radius:8px;border:1px solid var(--border);">';
            html += '<div style="font-weight:600;margin-bottom:8px;">' + escapeHtml(wf.name) + ' — 步骤</div>';
            steps.forEach(function(step, idx) {
                var condHtml = step.condition ? ' <span class="feature-badge orange">条件: ' + escapeHtml(step.condition) + '</span>' : '';
                var failHtml = step.onFailure ? ' <span class="feature-badge red">失败: ' + escapeHtml(step.onFailure) + '</span>' : '';
                html += '<div style="padding:4px 0;font-size:12px;color:var(--text-secondary);">' +
                    (idx+1) + '. <strong>' + escapeHtml(step.skill) + '</strong>' + condHtml + failHtml + '</div>';
            });
            html += '</div>';
        });
        html += '</div>';

        body.innerHTML = html;

        // Attach run buttons
        body.querySelectorAll('[data-workflow]').forEach(function(btn) {
            btn.addEventListener('click', async function() {
                var wfName = btn.dataset.workflow;
                btn.disabled = true;
                btn.textContent = '运行中...';
                try {
                    var runResp = await fetch(BASE + '/workflows/' + encodeURIComponent(wfName) + '/run', {
                        method: 'POST',
                        headers: authHeaders({ 'Content-Type': 'application/json' }),
                        body: '{}'
                    });
                    var runData = await runResp.json();
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
                    btn.textContent = '错误';
                    setTimeout(function() {
                        btn.disabled = false;
                        btn.textContent = '运行';
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
        var resp = await fetch(BASE + '/cost/summary?from=' + from + '&to=' + to, { headers: authHeaders() });
        if (!resp.ok) {
            body.innerHTML = featureEmpty('成本核算未启用或请求失败 (' + resp.status + ')');
            return;
        }
        var data = await resp.json();
        var summaries = data.summaries || [];
        if (summaries.length === 0) {
            body.innerHTML = featureEmpty('暂无成本记录。运行一次 skill 后再来查看。');
            return;
        }
        var html = '<div class="feature-stat-row">';
        var totalCost = 0, totalTokens = 0, totalReqs = 0;
        summaries.forEach(function(s) {
            totalCost += parseFloat(s.totalCost || 0);
            totalTokens += parseInt(s.totalTokens || 0);
            totalReqs += parseInt(s.requestCount || 0);
        });
        html += '<div class="feature-stat"><div class="feature-stat-value">' + totalCost.toFixed(2) + '</div><div class="feature-stat-label">总成本</div></div>';
        html += '<div class="feature-stat"><div class="feature-stat-value">' + totalTokens + '</div><div class="feature-stat-label">总 Tokens</div></div>';
        html += '<div class="feature-stat"><div class="feature-stat-value">' + totalReqs + '</div><div class="feature-stat-label">总请求数</div></div>';
        html += '</div>';

        html += '<table class="feature-table"><thead><tr><th>维度</th><th>值</th><th>成本</th><th>Tokens</th><th>请求数</th><th>预算利用率</th></tr></thead><tbody>';
        summaries.forEach(function(s) {
            var util = s.budget ? (s.utilization * 100).toFixed(1) + '%' : '—';
            html += '<tr><td>' + escapeHtml(s.dimension) + '</td><td>' + escapeHtml(s.dimensionValue) + '</td>' +
                '<td>' + (parseFloat(s.totalCost || 0)).toFixed(2) + '</td><td>' + (s.totalTokens || 0) + '</td>' +
                '<td>' + (s.requestCount || 0) + '</td><td>' + util + '</td></tr>';
        });
        html += '</tbody></table>';
        body.innerHTML = html;
    } catch (e) {
        body.innerHTML = featureEmpty('加载失败: ' + e.message);
    }
}

// --- Issues (Problem Closure) ---
async function showIssuesModal() {
    var modal = openFeatureModal('问题闭环', '<div class="feature-empty">加载中...</div>');
    var body = modal.querySelector('.history-modal-body');
    try {
        // Issue closure doesn't have a list-all endpoint; show explanatory info
        var resp = await fetch(BASE + '/runs?limit=10', { headers: authHeaders() });
        var data = await resp.json();
        var runs = data.runs || [];
        var html = '<div style="padding:8px 0;font-size:13px;color:var(--text-secondary);margin-bottom:12px;">' +
            '问题闭环功能在每个运行任务完成后可用。在聊天区完成一次诊断后，可对该任务发起"建议方案"、"创建 Issue"、"验证修复"和"关闭问题"操作。</div>';
        if (runs.length > 0) {
            html += '<table class="feature-table"><thead><tr><th>任务 ID</th><th>Skill</th><th>状态</th><th>操作</th></tr></thead><tbody>';
            runs.forEach(function(r) {
                var isDone = r.status === 'DONE';
                html += '<tr><td><code>' + escapeHtml(r.taskId) + '</code></td><td>' + escapeHtml(r.skillId || r.skill) + '</td>' +
                    '<td><span class="feature-badge ' + (isDone ? 'green' : 'orange') + '">' + escapeHtml(r.status) + '</span></td>' +
                    '<td>' + (isDone ? '<button class="feature-action-btn" data-task-id="' + escapeHtml(r.taskId) + '">建议方案</button>' : '—') + '</td></tr>';
            });
            html += '</tbody></table>';
        } else {
            html += featureEmpty('暂无运行记录');
        }
        body.innerHTML = html;

        // Attach solution buttons
        body.querySelectorAll('[data-task-id]').forEach(function(btn) {
            btn.addEventListener('click', async function() {
                var taskId = btn.dataset.taskId;
                btn.disabled = true;
                btn.textContent = '生成中...';
                try {
                    var solResp = await fetch(BASE + '/runs/' + encodeURIComponent(taskId) + '/solution', {
                        method: 'POST',
                        headers: authHeaders({ 'Content-Type': 'application/json' }),
                        body: '{}'
                    });
                    var solData = await solResp.json();
                    btn.textContent = '已生成';
                    btn.style.background = 'var(--green-light)';
                    btn.style.borderColor = 'var(--green)';
                    btn.style.color = 'var(--green)';
                    // Show solution in alert-like inline
                    var row = btn.closest('tr');
                    if (row && solData.solutions) {
                        var solHtml = '<tr><td colspan="4"><div style="padding:8px;background:var(--bg-card);border-radius:8px;">';
                        solData.solutions.forEach(function(sol, i) {
                            solHtml += '<div style="margin-bottom:6px;"><strong>方案' + (i+1) + ':</strong> ' + escapeHtml(sol.description || sol.title || '') +
                                ' <span class="feature-badge orange">' + escapeHtml(sol.recommendation || '') + '</span></div>';
                        });
                        solHtml += '</div></td></tr>';
                        row.insertAdjacentHTML('afterend', solHtml);
                    }
                } catch (e) {
                    btn.textContent = '失败';
                    btn.style.background = 'var(--red-light)';
                }
                setTimeout(function() {
                    btn.disabled = false;
                    btn.textContent = '建议方案';
                    btn.style.cssText = '';
                }, 5000);
            });
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
        var tasks = tasksData.tasks || [];
        var reports = reportsData.reports || [];

        var html = '<div style="margin-bottom:20px;">';
        html += '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">' +
            '<span style="font-size:13px;font-weight:600;color:var(--text-secondary);">巡检任务 (' + tasks.length + ')</span>' +
            '<button class="feature-action-btn" id="createPatrolBtn">新建巡检</button></div>';
        if (tasks.length === 0) {
            html += featureEmpty('无巡检任务');
        } else {
            html += '<table class="feature-table"><thead><tr><th>任务 ID</th><th>Skill</th><th>Cron</th><th>状态</th><th>上次运行</th></tr></thead><tbody>';
            tasks.forEach(function(t) {
                var badge = t.active ? 'green' : 'orange';
                html += '<tr><td><code>' + escapeHtml(t.taskId || t.id) + '</code></td><td>' + escapeHtml(t.skillId || t.skill) + '</td>' +
                    '<td>' + escapeHtml(t.cron || '') + '</td><td><span class="feature-badge ' + badge + '">' + (t.active ? '运行中' : '已停止') + '</span></td>' +
                    '<td>' + escapeHtml(t.lastRun || '—') + '</td></tr>';
            });
            html += '</tbody></table>';
        }
        html += '</div>';

        html += '<div>';
        html += '<div style="font-size:13px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">巡检报告 (' + reports.length + ')</div>';
        if (reports.length === 0) {
            html += featureEmpty('无巡检报告');
        } else {
            html += '<table class="feature-table"><thead><tr><th>报告 ID</th><th>时间</th><th>状态</th><th>摘要</th></tr></thead><tbody>';
            reports.forEach(function(r) {
                var badge = r.severity === 'CRITICAL' ? 'red' : (r.severity === 'WARN' ? 'orange' : 'green');
                html += '<tr><td><code>' + escapeHtml(r.reportId || r.id) + '</code></td><td>' + escapeHtml(r.timestamp || '') + '</td>' +
                    '<td><span class="feature-badge ' + badge + '">' + escapeHtml(r.severity || 'INFO') + '</span></td>' +
                    '<td>' + escapeHtml((r.summary || '').substring(0, 80)) + '</td></tr>';
            });
            html += '</tbody></table>';
        }
        html += '</div>';
        body.innerHTML = html;
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
        var html = '<table class="feature-table"><thead><tr><th>告警 ID</th><th>类型</th><th>服务</th><th>严重级别</th><th>消息</th><th>时间</th><th>操作</th></tr></thead><tbody>';
        alerts.forEach(function(a) {
            var badge = a.severity === 'CRITICAL' ? 'red' : (a.severity === 'WARN' ? 'orange' : 'green');
            html += '<tr><td><code>' + escapeHtml(a.alertId || a.id) + '</code></td><td>' + escapeHtml(a.type || '') + '</td>' +
                '<td>' + escapeHtml(a.service || '') + '</td><td><span class="feature-badge ' + badge + '">' + escapeHtml(a.severity || 'INFO') + '</span></td>' +
                '<td>' + escapeHtml(a.message || '') + '</td><td>' + escapeHtml(a.timestamp || '') + '</td>' +
                '<td><button class="feature-action-btn" data-alert-id="' + escapeHtml(a.alertId || a.id) + '">解决</button></td></tr>';
        });
        html += '</tbody></table>';
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
                    btn.textContent = '已解决';
                    btn.style.background = 'var(--green-light)';
                    btn.style.borderColor = 'var(--green)';
                    btn.style.color = 'var(--green)';
                } catch (e) {
                    btn.textContent = '失败';
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
