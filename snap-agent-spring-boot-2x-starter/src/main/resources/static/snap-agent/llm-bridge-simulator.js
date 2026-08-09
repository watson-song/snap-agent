/**
 * LLM Bridge Simulator — 测试用，注入到前端模拟插件行为
 *
 * 使用方式：
 *   1. 在浏览器 DevTools Console 中粘贴执行
 *   2. 或在 HTML 中 <script src="llm-bridge-simulator.js"></script>
 *
 * 功能：
 *   - 拦截 /snap-agent/bridge/llm/stream 的 SSE 事件
 *   - 收到 llm-request 后，直接用 fetch 调 LLM API
 *   - 将结果 POST 回 /snap-agent/bridge/llm/result
 *
 * LLM 配置从 Settings 页面保存的配置读取（localStorage）
 */
(function() {
    'use strict';

    console.log('[LLM Bridge Simulator] Loading...');

    var DEFAULT_CONFIG = {
        apiType: 'anthropic',
        baseUrl: 'https://claudecode.sf-express.com/ccr',
        apiKey: '',
        authToken: '01414185',
        model: 'aliyun/glm-5.2',
        maxTokens: 8192
    };

    // 从 localStorage 读配置（Settings 页面保存的）
    function getConfig() {
        try {
            var stored = localStorage.getItem('snap-agent-settings');
            if (stored) {
                var s = JSON.parse(stored);
                if (s && s.llm) {
                    return {
                        apiType: s.llm.apiType || DEFAULT_CONFIG.apiType,
                        baseUrl: s.llm.baseUrl || DEFAULT_CONFIG.baseUrl,
                        apiKey: s.llm.apiKey || '',
                        authToken: s.llm.authToken || DEFAULT_CONFIG.authToken,
                        model: s.llm.model || DEFAULT_CONFIG.model,
                        maxTokens: s.llm.maxTokens || DEFAULT_CONFIG.maxTokens
                    };
                }
            }
        } catch(e) {}
        return DEFAULT_CONFIG;
    }

    // 发送结果回 Server
    function sendResult(taskId, response) {
        fetch('/snap-agent/bridge/llm/result', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                id: taskId,
                text: response.text || '',
                toolCalls: response.toolCalls || [],
                usage: response.usage || { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0 }
            })
        }).then(function(r) {
            console.log('[Simulator] Result sent:', taskId, r.status);
        }).catch(function(e) {
            console.error('[Simulator] Failed to send result:', e);
        });
    }

    // 发送错误回 Server
    function sendError(taskId, error) {
        fetch('/snap-agent/bridge/llm/error', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: taskId, error: error })
        });
    }

    // 调 LLM API（直连，不走插件）
    function callLlm(request, config) {
        var url = config.baseUrl;
        if (config.apiType === 'anthropic' || config.apiType === '') {
            url = url.replace(/\/$/, '') + '/v1/messages';
        }

        var headers = { 'Content-Type': 'application/json' };
        if (config.apiKey) headers['x-api-key'] = config.apiKey;
        if (config.authToken) headers['Authorization'] = 'Bearer ' + config.authToken;
        if (config.apiType === 'anthropic' || config.apiType === '') {
            headers['anthropic-version'] = '2023-06-01';
        }

        var body = {
            model: config.model,
            max_tokens: config.maxTokens,
            messages: request.messages || [],
            system: request.systemPrompt || '',
            tools: request.tools || [],
            stream: false
        };

        console.log('[Simulator] Calling LLM:', url, 'model:', config.model);

        return fetch(url, { method: 'POST', headers: headers, body: JSON.stringify(body) })
            .then(function(resp) {
                if (!resp.ok) {
                    return resp.text().then(function(t) {
                        throw new Error('LLM API error ' + resp.status + ': ' + t);
                    });
                }
                return resp.json();
            })
            .then(function(data) {
                // Parse Anthropic response
                var text = '';
                var toolCalls = [];
                if (data.content) {
                    for (var i = 0; i < data.content.length; i++) {
                        var block = data.content[i];
                        if (block.type === 'text') text += block.text;
                        else if (block.type === 'tool_use') {
                            toolCalls.push({ id: block.id, name: block.name, input: block.input });
                        }
                    }
                }
                return {
                    text: text,
                    toolCalls: toolCalls,
                    usage: data.usage || { input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0 }
                };
            });
    }

    // 启动：拦截 SSE
    function start() {
        var config = getConfig();
        console.log('[LLM Bridge Simulator] Started with config:', {
            baseUrl: config.baseUrl,
            model: config.model,
            hasApiKey: !!config.apiKey,
            hasAuthToken: !!config.authToken
        });

        // 监听 bridge SSE
        var es = new EventSource('/snap-agent/bridge/llm/stream');

        es.addEventListener('llm-request', function(event) {
            try {
                var data = JSON.parse(event.data);
                var taskId = data.id;
                var request = data.request;
                console.log('[Simulator] Received request:', taskId);

                callLlm(request, config)
                    .then(function(result) {
                        console.log('[Simulator] LLM response:', result.text.substring(0, 100));
                        sendResult(taskId, result);
                    })
                    .catch(function(err) {
                        console.error('[Simulator] LLM error:', err.message);
                        sendError(taskId, err.message);
                    });
            } catch(e) {
                console.error('[Simulator] Parse error:', e);
            }
        });

        es.onerror = function(e) {
            console.error('[Simulator] SSE error:', e);
            // 自动重连
            setTimeout(start, 2000);
            es.close();
        };

        console.log('[LLM Bridge Simulator] Ready — listening for LLM requests');
        window.llmBridgeSimulator = { es: es, config: config };
    }

    start();
})();
