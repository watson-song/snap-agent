// SnapAgent SPA - Chat-style UI with real streaming & conversation history
// Version: v12-token (token-based auth support with localStorage)
console.log('[SnapAgent] app.js v12-token loaded');

const BASE = '/snap-agent';
let selectedSkill = null;
let currentStream = null;
let conversationHistory = []; // [{role, content}] for multi-turn

// ===== Auth config: read token source from server =====
let authConfig = { authHeader: '', authCookie: '', authLocalStorageKey: '' };

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
function selectSkill(skill, li) {
    document.querySelectorAll('.skill-list li').forEach(el => el.classList.remove('active'));
    li.classList.add('active');
    selectedSkill = skill;
    conversationHistory = []; // reset conversation on skill change

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
    if (skill.logPaths && skill.logPaths.length > 0) {
        inputsHint += `<div class="ctx-logpath">📄 日志目录: ${skill.logPaths.join(', ')}</div>`;
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

    // Clear chat
    document.getElementById('chatWelcome').style.display = 'none';
    document.getElementById('chatMessages').innerHTML = '';
    document.getElementById('messageInput').focus();
}

// ===== Send / Run =====
async function runSkill() {
    if (!selectedSkill) return;
    if (currentStream) {
        currentStream.close();
        currentStream = null;
    }

    // Collect inputs from form fields
    const inputs = {};
    const form = document.getElementById('inputForm');
    form.querySelectorAll('input, select, textarea').forEach(field => {
        if (field.name) inputs[field.name] = field.value;
    });

    // Get message from input bar
    const msgInput = document.getElementById('messageInput');
    const message = msgInput.value.trim();
    if (message) {
        // If the skill has a "message" input field that's empty, fill it
        if ('message' in inputs && !inputs.message) {
            inputs.message = message;
        } else if (!('message' in inputs)) {
            inputs._user_message = message;
        }
        msgInput.value = '';
        msgInput.style.height = 'auto';
    }

    // Don't send if nothing to send
    if (Object.keys(inputs).length === 0) return;

    const model = document.getElementById('modelSelect').value;

    // Show user message — just the value, no "key:" prefix
    const displayMsg = inputs.message || inputs._user_message
        || Object.values(inputs).join(', ');
    appendMessage('user', displayMsg);

    // Add to conversation history
    conversationHistory.push({ role: 'user', content: displayMsg });

    // Disable send button
    sendBtn.disabled = true;

    console.log('[runSkill] Sending POST /runs, skillId=', selectedSkill.name, 'model=', model);

    try {
        const resp = await fetch(`${BASE}/runs`, {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({
                skillId: selectedSkill.name,
                inputs,
                model,
                history: conversationHistory.length > 1 ? conversationHistory.slice(0, -1) : []
            })
        });
        if (handleAuthError(resp)) { updateSendButtonState(); return; }
        const data = await resp.json();
        console.log('[runSkill] POST /runs response:', data.status, 'taskId=', data.taskId);
        if (data.taskId) {
            subscribeStream(data.taskId);
        } else if (data.error) {
            appendMessage('error', data.message || data.error);
        }
    } catch (e) {
        console.error('[runSkill] POST /runs failed:', e);
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

    // EventSource sends cookies automatically (same-origin)
    const url = `${BASE}/runs/${taskId}/stream`;
    console.log('[subscribeStream] Creating EventSource for taskId=', taskId, 'url=', url);
    const es = new EventSource(url);
    currentStream = es;
    console.log('[subscribeStream] EventSource created, readyState=', es.readyState);

    es.onopen = function() {
        console.log('[SSE] connection opened, readyState=', es.readyState);
    };

    es.addEventListener('thought', e => {
        try {
            const data = JSON.parse(e.data);
            if (data.text) {
                if (!thoughtEl) {
                    thoughtEl = appendMessage('agent', '', true);
                    thoughtText = '';
                }
                thoughtText += data.text;
                allText += data.text;
                thoughtEl.classList.add('stream-cursor');
                // Debounce DOM updates: re-render at most once per animation frame
                // (~16ms) instead of on every token, to avoid blocking the EventSource
                // from consuming subsequent SSE events.
                if (!pendingRender) {
                    pendingRender = true;
                    requestAnimationFrame(() => {
                        pendingRender = false;
                        if (thoughtEl && thoughtText) {
                            try {
                                thoughtEl.querySelector('.msg-content').innerHTML = renderMarkdown(thoughtText);
                            } catch (renderErr) {
                                console.error('[SSE] renderMarkdown error:', renderErr);
                                thoughtEl.querySelector('.msg-content').textContent = thoughtText;
                            }
                            scrollChat();
                        }
                    });
                }
            }
        } catch (err) { /* ignore parse errors */ }
    });

    es.addEventListener('tool_call', e => {
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
            appendMessage('tool-call', `🔧 ${data.name}\n${JSON.stringify(data.args, null, 2)}`);
        } catch (err) {
            appendMessage('tool-call', e.data);
        }
    });

    es.addEventListener('tool_result', e => {
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
    });

    es.addEventListener('done', e => {
        console.log('[SSE] done event received, data:', e.data);
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
            // Add to conversation history
            if (allText) {
                conversationHistory.push({ role: 'assistant', content: allText });
            }
        } catch (doneErr) {
            console.error('[SSE] done handler error:', doneErr);
        } finally {
            es.close();
            currentStream = null;
            updateSendButtonState();
        }
    });

    // Custom event for task errors (not EventSource connection errors)
    es.addEventListener('task_error', e => {
        console.log('[SSE] task_error event received');
        try {
            const data = JSON.parse(e.data);
            appendMessage('error', data.text || '任务执行出错');
        } catch (err) {
            appendMessage('error', e.data || '任务执行出错');
        }
    });

    es.addEventListener('error', e => {
        console.log('[SSE] error event, readyState:', es.readyState);
        // If done handler already cleaned up, currentStream is null — nothing to do
        if (currentStream === null) return;
        // Connection dropped before done — clean up and re-enable UI
        if (thoughtEl) {
            thoughtEl.classList.remove('stream-cursor');
            thoughtEl = null;
        }
        es.close();
        currentStream = null;
        const messages = document.getElementById('chatMessages');
        const hint = document.createElement('div');
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
