/**
 * SnapAgent Bridge — Popup script
 *
 * Loads and saves the extension configuration. The content script
 * listens for storage changes and notifies the page.
 */

var masterToggle = document.getElementById('masterToggle');
var serviceIssueTracker = document.getElementById('serviceIssueTracker');
var serviceVcs = document.getElementById('serviceVcs');
var statusEl = document.getElementById('status');

// Load config
chrome.storage.local.get(['snapAgentConfig'], function(result) {
    var config = result.snapAgentConfig || {
        masterEnabled: true,
        services: { 'issue-tracker': true, 'vcs': true }
    };
    masterToggle.checked = config.masterEnabled;
    serviceIssueTracker.checked = config.services['issue-tracker'] !== false;
    serviceVcs.checked = config.services['vcs'] !== false;
    updateStatus(config);
});

function saveConfig() {
    var config = {
        masterEnabled: masterToggle.checked,
        services: {
            'issue-tracker': serviceIssueTracker.checked,
            'vcs': serviceVcs.checked
        }
    };
    chrome.storage.local.set({ snapAgentConfig: config }, function() {
        updateStatus(config);
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

masterToggle.addEventListener('change', saveConfig);
serviceIssueTracker.addEventListener('change', saveConfig);
serviceVcs.addEventListener('change', saveConfig);
