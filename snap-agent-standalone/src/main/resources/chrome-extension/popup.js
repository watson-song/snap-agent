/**
 * SnapAgent Bridge - Popup Script
 *
 * 功能：
 * 1. 主开关和服务开关
 * 2. 每宿主 LLM 配置
 * 3. 本地代码目录授权
 */

var masterToggle = document.getElementById('masterToggle');
var serviceIssueTracker = document.getElementById('serviceIssueTracker');
var serviceVcs = document.getElementById('serviceVcs');
var serviceLlm = document.getElementById('serviceLlm');
var statusEl = document.getElementById('status');
var currentHostEl = document.getElementById('currentHost');
var llmConfigSection = document.getElementById('llmConfigSection');

// LLM 配置字段
var llmApiKey = document.getElementById('llmApiKey');
var llmBaseUrl = document.getElementById('llmBaseUrl');
var llmModel = document.getElementById('llmModel');
var llmMaxTokens = document.getElementById('llmMaxTokens');
var saveLlmConfigBtn = document.getElementById('saveLlmConfig');

// 新增：目录授权字段
var dirNameEl = document.getElementById('dirName');
var chooseDirBtn = document.getElementById('chooseDirBtn');

// 获取当前域名
var currentDomain = window.location.origin;
currentHostEl.textContent = currentDomain;

// 加载配置
chrome.runtime.sendMessage({ type: 'snapagent-get-config' }, function(response) {
    var config = response || DEFAULT_CONFIG;
    masterToggle.checked = config.masterEnabled;
    serviceIssueTracker.checked = config.services['issue-tracker'] !== false;
    serviceVcs.checked = config.services['vcs'] !== false;
    serviceLlm.checked = config.services['llm'] !== false;
    
    // 显示 LLM 配置
    if (serviceLlm.checked) {
        llmConfigSection.style.display = 'block';
        loadLlmConfigForDomain(currentDomain, config.llmConfigs || {});
    }
    
    updateStatus(config);
});

// 加载已授权目录
chrome.runtime.sendMessage({ type: 'snapagent-get-code-dir' }, function(response) {
    if (response && response.dirName) {
        dirNameEl.textContent = response.dirName;
        dirNameEl.classList.add('authorized');
    } else {
        dirNameEl.textContent = 'None';
        dirNameEl.classList.remove('authorized');
    }
});

function loadLlmConfigForDomain(domain, llmConfigs) {
    var config = llmConfigs[domain] || {
        apiKey: '',
        baseUrl: 'https://api.anthropic.com',
        model: 'claude-sonnet-4-20250514',
        maxTokens: 8192
    };
    
    llmApiKey.value = config.apiKey || '';
    llmBaseUrl.value = config.baseUrl || 'https://api.anthropic.com';
    llmModel.value = config.model || 'claude-sonnet-4-20250514';
    llmMaxTokens.value = config.maxTokens || 8192;
}

function saveConfig() {
    var config = {
        masterEnabled: masterToggle.checked,
        services: {
            'issue-tracker': serviceIssueTracker.checked,
            'vcs': serviceVcs.checked,
            'llm': serviceLlm.checked
        }
    };
    
    chrome.runtime.sendMessage({ type: 'snapagent-save-config', config: config }, function() {
        updateStatus(config);
    });
}

function saveLlmConfig() {
    var llmConfig = {
        apiKey: llmApiKey.value.trim(),
        baseUrl: llmBaseUrl.value.trim() || 'https://api.anthropic.com',
        model: llmModel.value,
        maxTokens: parseInt(llmMaxTokens.value) || 8192
    };
    
    chrome.runtime.sendMessage({ 
        type: 'snapagent-save-llm-config',
        domain: currentDomain,
        config: llmConfig
    }, function() {
        saveLlmConfigBtn.textContent = 'Saved!';
        setTimeout(function() {
            saveLlmConfigBtn.textContent = 'Save for this host';
        }, 2000);
    });
}

function updateStatus(config) {
    if (!config.masterEnabled) {
        statusEl.textContent = 'Bridge is OFF';
        statusEl.style.color = '#cc6600';
    } else {
        var active = Object.keys(config.services).filter(function(k) {
            return config.services[k];
        });
        if (active.length > 0) {
            statusEl.textContent = 'Active: ' + active.join(', ');
            statusEl.style.color = '#006600';
        } else {
            statusEl.textContent = 'No services enabled';
            statusEl.style.color = '#cc6600';
        }
    }
}

// 事件监听
masterToggle.addEventListener('change', saveConfig);
serviceIssueTracker.addEventListener('change', saveConfig);
serviceVcs.addEventListener('change', saveConfig);
serviceLlm.addEventListener('change', function() {
    saveConfig();
    llmConfigSection.style.display = serviceLlm.checked ? 'block' : 'none';
});
saveLlmConfigBtn.addEventListener('click', saveLlmConfig);

// 新增：选择目录按钮
chooseDirBtn.addEventListener('click', function() {
    chrome.runtime.sendMessage({ type: 'snapagent-choose-dir' }, function(response) {
        if (response && response.success) {
            dirNameEl.textContent = response.dirName;
            dirNameEl.classList.add('authorized');
            chooseDirBtn.textContent = 'Change Directory';
            setTimeout(function() {
                chooseDirBtn.textContent = 'Choose Directory';
            }, 2000);
        } else {
            alert(response.error || 'Failed to select directory');
        }
    });
});

// 默认配置
var DEFAULT_CONFIG = {
    masterEnabled: true,
    services: {
        'issue-tracker': true,
        'vcs': true,
        'llm': true
    }
};
