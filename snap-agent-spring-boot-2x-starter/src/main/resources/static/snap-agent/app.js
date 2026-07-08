// SnapAgent SPA - Chat-style UI with real streaming & per-skill conversation history
// Version: v15-streamfix (robust stream cancellation + conversation save on switch)
console.log('[SnapAgent] app.js v15-streamfix loaded');

const BASE = '/snap-agent';
let selectedSkill = null;
let currentStream = null;
// Per-skill conversation state: { [skillId]: { conversationId, messages: [{role, content}] } }
let skillConversations = {};
let activeConversationId = null; // conversation ID for the currently selected skill
// Cancellation function for the active stream — called by selectSkill to stop ALL event processing
let streamCancelFn = null;

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
    // Priority: localStorage > cookie
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
        // Display username in top bar
        if (info.username) {
            document.getElementById('userName').textContent = info.username;
            document.getElementById('userInfo').style.display = 'flex';
        }
        // Store userId for SSE stream token auth (EventSource can't send headers)
        if (info.userId) {
            currentUserId = info.userId;
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
    // Avoid stacking multiple prompts
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
function toast(msg, type = 'success') {
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 3000);
}

// ===== Load Skills =====
async function loadSkills() {
    const resp = await fetch(`${BASE}/skills`, { headers: authHeaders() });
    if (handleAuthError(resp)) return;
    const data = await resp.json();
    const ul = document.getElementById('skills');
    ul.innerHTML = '';
    document.getElementById('skillCount').textContent = data.skills.length;
    data.skills.forEach(skill => {
        const li = document.createElement('li');
        li.dataset.skillId = skill.name;
        const badge = skill.availability === 'AVAILABLE'
            ? '<span class="skill-badge available">可用</span>'
            : '<span class="skill-badge unavailable">不可用</span>';
        li.innerHTML = `
            <div class="skill-item-name">${skill.name}${badge}</div>
            <div class="skill-item-desc">${skill.description || ''}</div>
        `;
        if (skill.availability !== 'AVAILABLE') {
            li.title = skill.unavailableReason || skill.availability;
            li.style.opacity = '0.5';
        }
        li.addEventListener('click', () => selectSkill(skill, li));
        ul.appendChild(li);
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

// ===== Select Skill =====
async function selectSkill(skill, li) {
    // Cancel and save the active stream before switching skills
    if (streamCancelFn) {
        streamCancelFn(); // sets cancelled flag + saves partial conversation
        streamCancelFn = null;
    }
    if (currentStream) {
        currentStream.close();
        currentStream = null;
        hideRobotWorking();
    }
    document.querySelectorAll('.skill-list li').forEach(el => el.classList.remove('active'));
    li.classList.add('active');
    selectedSkill = skill;

    // Load conversation for this skill (from memory map or backend)
    const skillName = skill.name;
    if (!skillConversations[skillName]) {
        // Try to load the latest conversation for this skill from backend
        const conv = await loadLatestConversation(skillName);
        if (conv) {
            skillConversations[skillName] = {
                conversationId: conv.conversationId,
                messages: conv.messages || []
            };
        } else {
            skillConversations[skillName] = { conversationId: null, messages: [] };
        }
    }
    activeConversationId = skillConversations[skillName].conversationId;

    // Update top bar context with required inputs reminder
    const ctx = document.getElementById('skillContext');
    let inputsHint = '';
    if (skill.inputs && skill.inputs.length > 0) {
        const required = skill.inputs.filter(i => i.required);
        if (required.length > 0) {
            inputsHint = `<div class="ctx-inputs">⚠️ 必须输入: ${required.map(i => i.label || i.key).join(', ')}</div>`;
        }
    }
    // Show log paths if available
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

    // Show input area
    const inputArea = document.getElementById('inputArea');
    inputArea.style.display = 'block';

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

    // Render input form
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
                field.appendChild(label);
                field.appendChild(inp);
            }
            form.appendChild(field);
        });
    }

    // Render chat from the conversation history
    const chatWelcome = document.getElementById('chatWelcome');
    const chatMessages = document.getElementById('chatMessages');
    chatMessages.innerHTML = '';
    const messages = skillConversations[skillName]?.messages || [];
    if (messages.length > 0) {
        chatWelcome.style.display = 'none';
        messages.forEach(msg => {
            if (msg.role === 'user') {
                appendMessage('user', msg.content);
            } else if (msg.role === 'assistant') {
                appendMessage('response', msg.content);
            }
        });
    } else {
        chatWelcome.style.display = 'flex';
    }
    document.getElementById('messageInput').focus();
}

// ===== Send / Run =====
async function runSkill() {
    if (!selectedSkill) return;
    // Cancel and save any active stream before starting a new one
    if (streamCancelFn) {
        streamCancelFn();
        streamCancelFn = null;
    }
    if (currentStream) {
        currentStream.close();
        currentStream = null;
    }

    // Get or create conversation for this skill
    const skillName = selectedSkill.name;
    if (!skillConversations[skillName]) {
        skillConversations[skillName] = { conversationId: null, messages: [] };
    }
    const conv = skillConversations[skillName];

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
        // If the skill has a "message" input field that's empty, fill it
        if ('message' in inputs && !inputs.message) {
            inputs.message = message;
        } else if (!('message' in inputs)) {
            inputs._user_message = message;
        }
        msgInputEl.value = '';
        msgInputEl.style.height = 'auto';
    }

    // Don't send if nothing to send
    if (Object.keys(inputs).length === 0) return;

    const model = document.getElementById('modelSelect').value;

    // Show user message — just the value, no "key:" prefix
    const displayMsg = inputs.message || inputs._user_message
        || Object.values(inputs).join(', ');
    appendMessage('user', displayMsg);

    // Add to conversation history
    conv.messages.push({ role: 'user', content: displayMsg });

    // Show robot working animation immediately
    showRobotWorking();

    // Disable send button
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
                history: conv.messages.length > 1 ? conv.messages.slice(0, -1) : []
            })
        });
        if (handleAuthError(resp)) { hideRobotWorking(); updateSendButtonState(); return; }
        const data = await resp.json();
        console.log('[runSkill] POST /runs response:', data.status, 'taskId=', data.taskId);
        if (data.taskId) {
            subscribeStream(data.taskId);
        } else if (data.error) {
            hideRobotWorking();
            appendMessage('error', data.message || data.error);
        }
    } catch (e) {
        console.error('[runSkill] POST /runs failed:', e);
        hideRobotWorking();
        appendMessage('error', '请求失败: ' + e.message);
    } finally {
        updateSendButtonState();
    }
}

// ===== Stream Subscription =====
function subscribeStream(taskId) {
    let thoughtEl = null;
    let thoughtText = '';
    let allText = ''; // accumulate all text for conversation history
    let pendingRender = false; // debounce DOM updates via requestAnimationFrame
    let cancelled = false; // set to true by streamCancelFn — stops ALL event processing
    // Capture the skill name at stream start so switching skills mid-stream doesn't leak
    const streamSkillName = selectedSkill ? selectedSkill.name : null;

    // Install the cancel function — called by selectSkill before closing the stream
    streamCancelFn = function() {
        cancelled = true;
        // Save accumulated assistant text to the correct (captured) skill's conversation
        if (allText && streamSkillName) {
            if (!skillConversations[streamSkillName]) {
                skillConversations[streamSkillName] = { conversationId: null, messages: [] };
            }
            var msgs = skillConversations[streamSkillName].messages;
            // Only push if last message isn't already an assistant message (avoid dupes)
            if (msgs.length === 0 || msgs[msgs.length - 1].role !== 'assistant') {
                msgs.push({ role: 'assistant', content: allText });
                saveConversationToBackend(streamSkillName);
            }
        }
    };

    // EventSource can't send custom headers — for token-auth projects,
    // append ?token=base64(userId:x) so the controller can identify the user
    // and skip the ownership check (task ID is already unguessable).
    let url = `${BASE}/runs/${taskId}/stream`;
    if (currentUserId) {
        const tokenParam = btoa(currentUserId + ':x');
        url += `?token=${encodeURIComponent(tokenParam)}`;
    }
    console.log('[subscribeStream] Creating EventSource for taskId=', taskId, 'url=', url);
    const es = new EventSource(url);
    currentStream = es;
    console.log('[subscribeStream] EventSource created, readyState=', es.readyState);

    es.onopen = function() {
        if (cancelled) return;
        console.log('[SSE] connection opened, readyState=', es.readyState);
        showRobotWorking();
    };

    es.addEventListener('thought', e => {
        if (cancelled) return;
        try {
            const data = JSON.parse(e.data);
            if (data.text) {
                allText += data.text;
                if (!thoughtEl) {
                    hideRobotWorking();
                    thoughtEl = appendMessage('agent', '', true);
                    thoughtText = '';
                }
                thoughtText += data.text;
                thoughtEl.classList.add('stream-cursor');
                // Debounce DOM updates: re-render at most once per animation frame
                // (~16ms) instead of on every token, to avoid blocking the EventSource
                // from consuming subsequent SSE events.
                if (!pendingRender) {
                    pendingRender = true;
                    requestAnimationFrame(() => {
                        pendingRender = false;
                        if (cancelled || !thoughtEl || !thoughtText) return;
                        try {
                            thoughtEl.querySelector('.msg-content').innerHTML = renderMarkdown(thoughtText);
                        } catch (renderErr) {
                            console.error('[SSE] renderMarkdown error:', renderErr);
                            thoughtEl.querySelector('.msg-content').textContent = thoughtText;
                        }
                        scrollChat();
                    });
                }
            }
        } catch (err) { /* ignore parse errors */ }
    });

    es.addEventListener('tool_call', e => {
        if (cancelled) return;
        if (thoughtEl) {
            if (pendingRender) {
                pendingRender = false;
                if (thoughtText) {
                    thoughtEl.querySelector('.msg-content').innerHTML = renderMarkdown(thoughtText);
                }
            }
            thoughtEl.classList.remove('stream-cursor');
            thoughtEl.querySelector('.msg-label').textContent = '思考';
            thoughtEl.classList.remove('msg-agent');
            thoughtEl.classList.add('msg-thought');
            thoughtEl = null;
            thoughtText = '';
        }
        showRobotWorking();
        try {
            const data = JSON.parse(e.data);
            appendMessage('tool-call', `🔧 ${data.name}\n${JSON.stringify(data.args, null, 2)}`);
        } catch (err) {
            appendMessage('tool-call', e.data);
        }
    });

    es.addEventListener('tool_result', e => {
        if (cancelled) return;
        if (thoughtEl) {
            if (pendingRender) {
                pendingRender = false;
                if (thoughtText) {
                    thoughtEl.querySelector('.msg-content').innerHTML = renderMarkdown(thoughtText);
                }
            }
            thoughtEl.classList.remove('stream-cursor');
            thoughtEl.querySelector('.msg-label').textContent = '思考';
            thoughtEl.classList.remove('msg-agent');
            thoughtEl.classList.add('msg-thought');
            thoughtEl = null;
            thoughtText = '';
        }
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
            appendMessage('tool-result', display);
        } catch (err) {
            appendMessage('tool-result', e.data);
        }
        showRobotWorking();
    });

    es.addEventListener('done', e => {
        console.log('[SSE] done event received, data:', e.data);
        // If cancelled, the cancel function already saved the conversation
        if (cancelled) {
            es.close();
            currentStream = null;
            streamCancelFn = null;
            return;
        }
        hideRobotWorking();
        try {
            if (thoughtEl) {
                // Flush any pending render before finalizing
                if (pendingRender) {
                    pendingRender = false;
                    if (thoughtText) {
                        try {
                            thoughtEl.querySelector('.msg-content').innerHTML = renderMarkdown(thoughtText);
                        } catch (renderErr) {
                            console.error('[SSE] renderMarkdown error on done:', renderErr);
                            thoughtEl.querySelector('.msg-content').textContent = thoughtText;
                        }
                    }
                }
                // This was the final response — keep it as "agent" style (no "思考" label)
                thoughtEl.classList.remove('stream-cursor');
                thoughtEl.querySelector('.msg-label').textContent = '回复';
                thoughtEl.classList.remove('msg-thought');
                thoughtEl.classList.add('msg-response');
                thoughtEl = null;
                thoughtText = '';
            }
            // Subtle completion indicator
            let status = '完成';
            try {
                const data = JSON.parse(e.data);
                status = data.status || '完成';
            } catch (err) {
                // Data is a plain string (e.g., "SUCCEEDED") — use directly
                if (e.data && e.data.trim()) {
                    status = e.data.trim();
                }
            }
            appendCompletion(status);
            // Add to per-skill conversation history and save to backend
            if (allText && streamSkillName) {
                if (!skillConversations[streamSkillName]) {
                    skillConversations[streamSkillName] = { conversationId: null, messages: [] };
                }
                skillConversations[streamSkillName].messages.push({ role: 'assistant', content: allText });
                saveConversationToBackend(streamSkillName);
            }
        } catch (doneErr) {
            console.error('[SSE] done handler error:', doneErr);
        } finally {
            streamCancelFn = null;
            es.close();
            currentStream = null;
            updateSendButtonState();
        }
    });

    // Custom event for task errors (not EventSource connection errors)
    es.addEventListener('task_error', e => {
        if (cancelled) return;
        console.log('[SSE] task_error event received');
        hideRobotWorking();
        try {
            const data = JSON.parse(e.data);
            appendMessage('error', data.text || '任务执行出错');
        } catch (err) {
            appendMessage('error', e.data || '任务执行出错');
        }
    });

    es.addEventListener('error', e => {
        console.log('[SSE] error event, readyState:', es.readyState);
        if (cancelled) return;
        hideRobotWorking();
        // If done handler already cleaned up, currentStream is null — nothing to do
        if (currentStream === null) return;
        // Connection dropped before done — clean up and re-enable UI
        if (thoughtEl) {
            thoughtEl.classList.remove('stream-cursor');
            thoughtEl = null;
        }
        // Save partial conversation on connection drop
        if (allText && streamSkillName) {
            if (!skillConversations[streamSkillName]) {
                skillConversations[streamSkillName] = { conversationId: null, messages: [] };
            }
            var msgs = skillConversations[streamSkillName].messages;
            if (msgs.length === 0 || msgs[msgs.length - 1].role !== 'assistant') {
                msgs.push({ role: 'assistant', content: allText });
                saveConversationToBackend(streamSkillName);
            }
        }
        streamCancelFn = null;
        es.close();
        currentStream = null;
        var messages = document.getElementById('chatMessages');
        var hint = document.createElement('div');
        hint.className = 'msg-completion';
        hint.textContent = '— 连接断开，请重新发送消息继续 —';
        messages.appendChild(hint);
        scrollChat();
        updateSendButtonState();
    });
}

// ===== Append Message =====
function appendMessage(type, content, returnEl = false) {
    const messages = document.getElementById('chatMessages');
    const el = document.createElement('div');
    el.className = `msg msg-${type}`;

    const labelMap = {
        'thought': '思考',
        'agent': '回复',
        'tool-call': '工具调用',
        'tool-result': '工具结果',
        'error': '错误',
        'user': '你',
    };

    if (labelMap[type]) {
        const label = document.createElement('div');
        label.className = 'msg-label';
        label.textContent = labelMap[type];
        el.appendChild(label);
    }

    const contentEl = document.createElement('div');
    contentEl.className = 'msg-content';

    // Use Markdown rendering for agent/response/thought messages
    // Use plain text for tool calls, results, errors
    if (type === 'agent' || type === 'thought' || type === 'response') {
        contentEl.innerHTML = renderMarkdown(content);
    } else {
        contentEl.textContent = content;
    }
    el.appendChild(contentEl);

    messages.appendChild(el);
    scrollChat();
    return returnEl ? el : null;
}

// ===== Subtle Completion Indicator =====
function appendCompletion(status) {
    const messages = document.getElementById('chatMessages');
    const el = document.createElement('div');
    el.className = 'msg-completion';
    el.textContent = `— ${status} —`;
    messages.appendChild(el);
    scrollChat();
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
    // Determine folder name from first file path
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
    const conv = skillConversations[skillName];
    if (!conv || conv.messages.length === 0) return;
    try {
        const resp = await fetch(`${BASE}/conversations`, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({
                conversationId: conv.conversationId,
                skillId: skillName,
                messages: conv.messages.map(m => ({
                    role: m.role,
                    content: m.content,
                    timestamp: Date.now()
                }))
            })
        });
        if (resp.ok) {
            const data = await resp.json();
            conv.conversationId = data.conversationId;
            activeConversationId = data.conversationId;
            console.log('[Conversation] saved:', data.conversationId, 'messages:', data.messageCount);
        }
    } catch (e) {
        console.error('[Conversation] save failed:', e);
    }
}

async function loadLatestConversation(skillId) {
    try {
        const resp = await fetch(`${BASE}/conversations?skillId=${encodeURIComponent(skillId)}`,
            { headers: authHeaders() });
        if (!resp.ok) return null;
        const data = await resp.json();
        if (!data.conversations || data.conversations.length === 0) return null;
        // Load the first (newest) conversation
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
                content: m.content
            }))
        };
    } catch (e) {
        console.error('[Conversation] load failed:', e);
        return null;
    }
}

async function showHistoryModal() {
    // Remove existing modal
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

    // Load conversations filtered by the currently selected skill
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
                showHistoryModal(); // refresh
            });
            body.appendChild(item);
        });
    } catch (e) {
        body.innerHTML = '<div class="history-error">加载失败: ' + e.message + '</div>';
    }
}

async function restoreConversation(conversationId, skillId) {
    // Find the skill in the sidebar and select it
    const skillLi = document.querySelector(`.skill-list li[data-skill-id="${skillId}"]`);
    let skill = null;
    if (skillLi) {
        // Re-fetch skills to get the skill object
        const resp = await fetch(`${BASE}/skills`, { headers: authHeaders() });
        if (resp.ok) {
            const data = await resp.json();
            skill = data.skills.find(s => s.name === skillId);
        }
    }
    if (!skill) {
        toast('找不到对应的 Skill: ' + skillId, 'error');
        return;
    }

    // Load the conversation
    const conv = await loadConversationById(conversationId);
    if (!conv) {
        toast('加载会话失败', 'error');
        return;
    }

    // Set the conversation state
    skillConversations[skillId] = {
        conversationId: conv.conversationId,
        messages: conv.messages
    };

    // Select the skill (this will render the conversation)
    if (skillLi) {
        selectSkill(skill, skillLi);
    }
    toast('会话已加载', 'success');
}

function downloadConversation(conversationId) {
    // Use a hidden anchor to trigger download with auth header
    // Since we can't add headers to a direct download link, use fetch + blob
    fetch(`${BASE}/conversations/${conversationId}/download`, {
        headers: authHeaders()
    }).then(resp => {
        if (!resp.ok) throw new Error('Download failed');
        // Extract filename from Content-Disposition
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
            // If this was the active conversation, clear it
            for (const skillName in skillConversations) {
                if (skillConversations[skillName].conversationId === conversationId) {
                    skillConversations[skillName] = { conversationId: null, messages: [] };
                    if (selectedSkill && selectedSkill.name === skillName) {
                        activeConversationId = null;
                        document.getElementById('chatMessages').innerHTML = '';
                        document.getElementById('chatWelcome').style.display = 'flex';
                    }
                    break;
                }
            }
        }
    } catch (e) {
        toast('删除失败: ' + e.message, 'error');
    }
}

// ===== Sidebar Toggle =====
document.getElementById('sidebarToggle').addEventListener('click', () => {
    const sidebar = document.getElementById('sidebar');
    sidebar.classList.toggle('collapsed');
    const btn = document.getElementById('sidebarToggle');
    btn.textContent = sidebar.classList.contains('collapsed') ? '›' : '‹';
});

// ===== Robot Working Animation =====
function showRobotWorking() {
    hideRobotWorking();
    // Hide welcome screen if still visible
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
    // Skip during IME composition (Chinese/Japanese/Korean input)
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
// Also update button state when form fields change
document.getElementById('inputForm').addEventListener('input', updateSendButtonState);
document.getElementById('inputForm').addEventListener('change', updateSendButtonState);

// ===== Init =====
(async function() {
    await loadAuthConfig();
    const ok = await checkUserStatus();
    if (ok) {
        loadSkills();
        loadModels();
    }
})();
