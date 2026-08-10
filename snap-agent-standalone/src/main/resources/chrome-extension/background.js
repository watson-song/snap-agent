/**
 * SnapAgent Bridge - Service Worker (background.js)
 *
 * 功能：
 * 1. HTTP 代理（Issue Tracker / VCS / LLM）
 * 2. 本地文件读取（通过 chrome.fileSystem API）
 */

// 默认配置
var DEFAULT_CONFIG = {
    masterEnabled: true,
    services: {
        'issue-tracker': true,
        'vcs': true,
        'llm': true
    },
    // 本地代码目录授权
    codeDirEntryId: null
};

// 加载配置
chrome.runtime.onInstalled.addListener(function() {
    chrome.storage.local.get(['snapAgentConfig'], function(result) {
        if (!result.snapAgentConfig) {
            chrome.storage.local.set({ snapAgentConfig: DEFAULT_CONFIG });
        }
    });
});

// 处理消息
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
    
    // 新增：本地文件读取
    if (message.type === 'snapagent-read-file') {
        handleReadLocalFile(message.filePath, sendResponse);
        return true;
    }
    
    // 新增：选择代码目录
    if (message.type === 'snapagent-choose-dir') {
        handleChooseDirectory(sendResponse);
        return true;
    }
    
    // 新增：获取已授权目录
    if (message.type === 'snapagent-get-code-dir') {
        handleGetCodeDirectory(sendResponse);
        return true;
    }
});

// 现有：HTTP 代理
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
        resp.headers.forEach(function(v, k) { headers[k] = v; });
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

// 现有：LLM 代理
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
        stream: false
    };

    fetch(url, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(body)
    }).then(function(resp) {
        return resp.json();
    }).then(function(data) {
        var text = '';
        var toolCalls = [];
        
        if (data.content) {
            for (var i = 0; i < data.content.length; i++) {
                var block = data.content[i];
                if (block.type === 'text') text += block.text;
                else if (block.type === 'tool_use') {
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

// 新增：读取本地文件
function handleReadLocalFile(filePath, sendResponse) {
    chrome.storage.local.get(['snapAgentConfig'], function(result) {
        var config = result.snapAgentConfig || DEFAULT_CONFIG;
        var entryId = config.codeDirEntryId;
        
        if (!entryId) {
            sendResponse({
                error: 'No code directory authorized. Please select a directory in the extension popup.'
            });
            return;
        }
        
        chrome.fileSystem.restoreEntry(entryId, function(entry) {
            if (!entry) {
                sendResponse({ error: 'Failed to restore directory entry. Please re-authorize.' });
                return;
            }
            
            // 获取文件
            entry.getFile(filePath, {}, function(file) {
                if (chrome.runtime.lastError) {
                    sendResponse({ error: 'File not found: ' + filePath });
                    return;
                }
                
                var reader = new FileReader();
                reader.onload = function() {
                    sendResponse({
                        path: filePath,
                        content: reader.result
                    });
                };
                reader.onerror = function() {
                    sendResponse({ error: 'Failed to read file: ' + filePath });
                };
                reader.readAsText(file);
            }, function() {
                sendResponse({ error: 'Failed to access file: ' + filePath });
            });
        });
    });
}

// 新增：选择代码目录
function handleChooseDirectory(sendResponse) {
    chrome.fileSystem.chooseEntry({ type: 'openDirectory' }, function(entry) {
        if (!entry) {
            sendResponse({ error: 'User cancelled directory selection' });
            return;
        }
        
        // 保存授权
        chrome.fileSystem.retainEntry(entry, function(entryId) {
            if (chrome.runtime.lastError) {
                sendResponse({ error: 'Failed to save directory authorization' });
                return;
            }
            
            // 更新配置
            chrome.storage.local.get(['snapAgentConfig'], function(result) {
                var config = result.snapAgentConfig || DEFAULT_CONFIG;
                config.codeDirEntryId = entryId;
                config.codeDirName = entry.name;
                chrome.storage.local.set({ snapAgentConfig: config }, function() {
                    sendResponse({
                        success: true,
                        dirName: entry.name,
                        entryId: entryId
                    });
                });
            });
        });
    });
}

// 新增：获取已授权目录
function handleGetCodeDirectory(sendResponse) {
    chrome.storage.local.get(['snapAgentConfig'], function(result) {
        var config = result.snapAgentConfig || DEFAULT_CONFIG;
        sendResponse({
            entryId: config.codeDirEntryId,
            dirName: config.codeDirName || null
        });
    });
}

// 现有：LLM 配置管理
function getLlmConfigForDomain(domain) {
    // TODO: 从存储中读取
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
