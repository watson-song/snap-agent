/**
 * SnapAgent Bridge — Service Worker (background.js)
 *
 * Handles:
 * 1. Issue Tracker / VCS proxy (existing)
 * 2. LLM proxy (new) — streams LLM API calls through extension
 */

// Default config
var DEFAULT_CONFIG = {
    masterEnabled: true,
    services: {
        'issue-tracker': true,
        'vcs': true,
        'llm': true
    },
    // Per-domain LLM configs (multi-host isolation)
    llmConfigs: {}
};

// Load config on install
chrome.runtime.onInstalled.addListener(function() {
    chrome.storage.local.get(['snapAgentConfig'], function(result) {
        if (!result.snapAgentConfig) {
            chrome.storage.local.set({ snapAgentConfig: DEFAULT_CONFIG });
        }
    });
});

// Handle messages from content scripts
chrome.runtime.onMessage.addListener(function(message, sender, sendResponse) {
    if (message.type === 'snapagent-fetch') {
        handleFetch(message.request, sender, sendResponse);
        return true;
    }
    if (message.type === 'snapagent-get-config') {
        chrome.storage.local.get(['snapAgentConfig'], function(result) {
            sendResponse(result.snapAgentConfig || DEFAULT_CONFIG);
        });
        return true;
    }
    if (message.type === 'snapagent-llm-request') {
        handleLlmRequest(message.request, sender, sendResponse);
        return true;
    }
    if (message.type === 'snapagent-save-llm-config') {
        handleSaveLlmConfig(message.domain, message.config, sendResponse);
        return true;
    }
    if (message.type === 'snapagent-get-llm-config') {
        handleGetLlmConfig(message.domain, sendResponse);
        return true;
    }
});

// ---- Issue Tracker / VCS proxy (existing) ----

function handleFetch(request, sender, sendResponse) {
    var init = {
        method: request.method || 'GET',
        headers: {}
    };
    if (request.headers) {
        for (var key in request.headers) {
            init.headers[key] = request.headers[key];
        }
    }
    if (request.body && request.method !== 'GET' && request.method !== 'HEAD') {
        init.body = typeof request.body === 'string' ? request.body : JSON.stringify(request.body);
    }

    fetch(request.url, init).then(function(resp) {
        var headers = {};
        resp.headers.forEach(function(val, key) {
            headers[key] = val;
        });
        return resp.text().then(function(body) {
            sendResponse({
                id: request.id,
                status: resp.status,
                body: body,
                headers: headers
            });
        });
    }).catch(function(e) {
        sendResponse({
            id: request.id,
            error: e.message || 'Extension fetch failed'
        });
    });
}

// ---- LLM proxy (new) ----

function handleLlmRequest(request, sender, sendResponse) {
    var domain = request.domain;
    var llmConfig = getLlmConfigForDomain(domain);
    
    if (!llmConfig || !llmConfig.apiKey) {
        sendResponse({
            id: request.id,
            error: 'LLM not configured for domain: ' + domain
        });
        return;
    }

    var baseUrl = llmConfig.baseUrl || 'https://api.anthropic.com';
    var url = baseUrl + '/v1/messages';
    
    var headers = {
        'Content-Type': 'application/json',
        'x-api-key': llmConfig.apiKey,
        'anthropic-version': '2023-06-01'
    };
    
    var body = {
        model: llmConfig.model || 'claude-sonnet-4-20250514',
        max_tokens: llmConfig.maxTokens || 8192,
        messages: request.messages || [],
        system: request.system || '',
        tools: request.tools || [],
        stream: false  // Collect full response, then stream back
    };

    fetch(url, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(body)
    }).then(function(resp) {
        return resp.json();
    }).then(function(data) {
        // Parse response
        var text = '';
        var toolCalls = [];
        
        if (data.content) {
            for (var i = 0; i < data.content.length; i++) {
                var block = data.content[i];
                if (block.type === 'text') {
                    text += block.text;
                } else if (block.type === 'tool_use') {
                    toolCalls.push({
                        id: block.id,
                        name: block.name,
                        input: block.input
                    });
                }
            }
        }
        
        sendResponse({
            id: request.id,
            text: text,
            toolCalls: toolCalls,
            usage: data.usage || { input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0 }
        });
    }).catch(function(e) {
        sendResponse({
            id: request.id,
            error: 'LLM API error: ' + e.message
        });
    });
}

// ---- Multi-domain LLM config ----

function getLlmConfigForDomain(domain) {
    // This will be called after loading config from storage
    // For now, return null - actual implementation loads from chrome.storage.local
    return null;
}

function handleSaveLlmConfig(domain, config, sendResponse) {
    chrome.storage.local.get(['snapAgentConfig'], function(result) {
        var snapConfig = result.snapAgentConfig || DEFAULT_CONFIG;
        if (!snapConfig.llmConfigs) {
            snapConfig.llmConfigs = {};
        }
        snapConfig.llmConfigs[domain] = config;
        chrome.storage.local.set({ snapAgentConfig: snapConfig }, function() {
            sendResponse({ success: true });
        });
    });
}

function handleGetLlmConfig(domain, sendResponse) {
    chrome.storage.local.get(['snapAgentConfig'], function(result) {
        var snapConfig = result.snapAgentConfig || DEFAULT_CONFIG;
        var config = (snapConfig.llmConfigs && snapConfig.llmConfigs[domain]) || {
            apiKey: '',
            baseUrl: 'https://api.anthropic.com',
            model: 'claude-sonnet-4-20250514',
            maxTokens: 8192
        };
        sendResponse(config);
    });
}
