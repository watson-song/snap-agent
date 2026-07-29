/**
 * SnapAgent Bridge — Service Worker (background.js)
 *
 * Listens for proxy-request messages from content scripts and performs
 * the actual HTTP fetch using the extension's host_permissions (bypassing CORS).
 */

// Default config
var DEFAULT_CONFIG = {
    masterEnabled: true,
    services: {
        'issue-tracker': true,
        'vcs': true
    }
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
        return true; // keep channel open for async response
    }
    if (message.type === 'snapagent-get-config') {
        chrome.storage.local.get(['snapAgentConfig'], function(result) {
            sendResponse(result.snapAgentConfig || DEFAULT_CONFIG);
        });
        return true;
    }
});

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
