/**
 * SnapAgent Bridge — Popup script
 *
 * Handles:
 * 1. Master switch and service toggles
 * 2. Per-domain LLM configuration (multi-host isolation)
 */

var masterToggle = document.getElementById('masterToggle');
var serviceIssueTracker = document.getElementById('serviceIssueTracker');
var serviceVcs = document.getElementById('serviceVcs');
var serviceLlm = document.getElementById('serviceLlm');
var statusEl = document.getElementById('status');
var currentHostEl = document.getElementById('currentHost');
var llmConfigSection = document.getElementById('llmConfigSection');

// LLM config fields
var llmApiKey = document.getElementById('llmApiKey');
var llmBaseUrl = document.getElementById('llmBaseUrl');
var llmModel = document.getElementById('llmModel');
var llmMaxTokens = document.getElementById('llmMaxTokens');
var saveLlmConfigBtn = document.getElementById('saveLlmConfig');

// Get current domain
var currentDomain = window.location.origin;
currentHostEl.textContent = currentDomain;

// Load config
chrome.storage.local.get(['snapAgentConfig'], function(result) {
    var config = result.snapAgentConfig || {
        masterEnabled: true,
        services: { 'issue-tracker': true, 'vcs': true, 'llm': true },
        llmConfigs: {}
    };
    
    masterToggle.checked = config.masterEnabled;
    serviceIssueTracker.checked = config.services['issue-tracker'] !== false;
    serviceVcs.checked = config.services['vcs'] !== false;
    serviceLlm.checked = config.services['llm'] !== false;
    
    // Show/hide LLM config section
    if (serviceLlm.checked) {
        llmConfigSection.style.display = 'block';
        loadLlmConfigForDomain(currentDomain, config.llmConfigs || {});
    }
    
    updateStatus(config);
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
    
    chrome.storage.local.get(['snapAgentConfig'], function(result) {
        var existing = result.snapAgentConfig || {};
        config.llmConfigs = existing.llmConfigs || {};
        
        chrome.storage.local.set({ snapAgentConfig: config }, function() {
            updateStatus(config);
        });
    });
}

function saveLlmConfig() {
    var llmConfig = {
        apiKey: llmApiKey.value.trim(),
        baseUrl: llmBaseUrl.value.trim() || 'https://api.anthropic.com',
        model: llmModel.value,
        maxTokens: parseInt(llmMaxTokens.value) || 8192
    };
    
    chrome.storage.local.get(['snapAgentConfig'], function(result) {
        var config = result.snapAgentConfig || {
            masterEnabled: true,
            services: { 'issue-tracker': true, 'vcs': true, 'llm': true },
            llmConfigs: {}
        };
        
        if (!config.llmConfigs) {
            config.llmConfigs = {};
        }
        config.llmConfigs[currentDomain] = llmConfig;
        
        chrome.storage.local.set({ snapAgentConfig: config }, function() {
            saveLlmConfigBtn.textContent = 'Saved!';
            setTimeout(function() {
                saveLlmConfigBtn.textContent = 'Save for this host';
            }, 2000);
        });
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

// Event listeners
masterToggle.addEventListener('change', saveConfig);
serviceIssueTracker.addEventListener('change', saveConfig);
serviceVcs.addEventListener('change', saveConfig);
serviceLlm.addEventListener('change', function() {
    saveConfig();
    llmConfigSection.style.display = serviceLlm.checked ? 'block' : 'none';
});
saveLlmConfigBtn.addEventListener('click', saveLlmConfig);
