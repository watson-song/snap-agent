/**
 * SnapAgent Bridge — Content Script
 *
 * Runs on every page. Detects if the SnapAgent web UI is present (via the
 * bridgeIndicator element or bridge-client.js). If so, bridges postMessage
 * communication between the page and the background service worker.
 *
 * Also notifies the page when the extension config changes.
 */

var SNAP_AGENT_BASE = '/snap-agent';

// Check if this page has SnapAgent loaded
function isSnapAgentPage() {
    return document.getElementById('bridgeIndicator') !== null
        || document.getElementById('bridgeClientScript') !== null
        || (window.location.pathname && window.location.pathname.indexOf(SNAP_AGENT_BASE) !== -1);
}

// Get config from background and notify the page
function notifyConfigToPage() {
    chrome.runtime.sendMessage({ type: 'snapagent-get-config' }, function(config) {
        if (chrome.runtime.lastError) {
            // Extension context might be invalidated
            return;
        }
        var status = {
            installed: true,
            masterEnabled: config ? config.masterEnabled : false,
            services: config ? config.services : {}
        };
        window.postMessage({
            type: 'snapagent-bridge-status',
            status: status
        }, '*');
    });
}

// Listen for proxy-request messages from the page, forward to background
window.addEventListener('message', function(event) {
    if (event.data && event.data.type === 'snapagent-proxy-request') {
        var req = event.data.request;
        chrome.runtime.sendMessage({ type: 'snapagent-fetch', request: req }, function(response) {
            if (chrome.runtime.lastError) {
                // Extension context invalidated — send error back
                window.postMessage({
                    type: 'snapagent-proxy-response',
                    response: {
                        id: req.id,
                        error: 'Extension context invalidated'
                    }
                }, '*');
                return;
            }
            window.postMessage({
                type: 'snapagent-proxy-response',
                response: response
            }, '*');
        });
    }
});

// Listen for storage changes and re-notify the page
chrome.storage.onChanged.addListener(function(changes, areaName) {
    if (areaName === 'local' && changes.snapAgentConfig) {
        notifyConfigToPage();
    }
});

// Initial notification on page load
if (isSnapAgentPage()) {
    notifyConfigToPage();
}

// Also notify when bridge-client.js loads dynamically
var observer = new MutationObserver(function(mutations) {
    mutations.forEach(function(mutation) {
        if (mutation.type === 'childList') {
            mutation.addedNodes.forEach(function(node) {
                if (node.id === 'bridgeClientScript' || node.id === 'bridgeIndicator') {
                    notifyConfigToPage();
                }
            });
        }
    });
});
observer.observe(document.documentElement, { childList: true, subtree: true });
