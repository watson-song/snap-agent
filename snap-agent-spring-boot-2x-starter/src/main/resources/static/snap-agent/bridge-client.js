/**
 * SnapAgent Bridge Client
 *
 * Connects to the SnapAgent SSE bridge endpoint, receives proxy-request events,
 * dispatches them through the Chrome Extension (if installed) or direct fetch,
 * and posts results back to the server.
 *
 * Also reports the extension's status to the server so the server knows whether
 * to route HTTP requests through the bridge.
 */
(function() {
    'use strict';

    var BRIDGE_BASE = '/snap-agent/bridge';
    var sse = null;
    var bridgeActive = false;

    // Extension status (reported by the Chrome Extension via postMessage)
    var extensionStatus = {
        installed: false,
        masterEnabled: false,
        services: {}
    };

    // ===== Status reporting =====

    function reportStatus() {
        fetch(BRIDGE_BASE + '/status-update', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(extensionStatus)
        }).catch(function(e) {
            console.warn('[BridgeClient] Failed to report status:', e);
        });
    }

    // Listen for extension status messages
    window.addEventListener('message', function(event) {
        if (event.data && event.data.type === 'snapagent-bridge-status') {
            extensionStatus = event.data.status;
            reportStatus();
            updateIndicator();
        }
    });

    // ===== Indicator UI =====

    function ensureIndicator() {
        var existing = document.getElementById('bridgeIndicator');
        if (existing) return existing;
        var el = document.createElement('div');
        el.id = 'bridgeIndicator';
        el.style.cssText = 'position:fixed;bottom:8px;right:8px;z-index:99999;'
            + 'padding:4px 8px;border-radius:4px;font-size:11px;'
            + 'background:#333;color:#999;cursor:default;user-select:none;';
        el.textContent = 'Bridge: OFF';
        document.body.appendChild(el);
        return el;
    }

    function updateIndicator() {
        var el = ensureIndicator();
        if (!extensionStatus.installed) {
            el.textContent = 'Bridge: Extension not installed';
            el.style.background = '#660000';
            el.style.color = '#ff6666';
        } else if (!extensionStatus.masterEnabled) {
            el.textContent = 'Bridge: Master switch OFF';
            el.style.background = '#664400';
            el.style.color = '#ffcc66';
        } else {
            var activeServices = Object.keys(extensionStatus.services)
                .filter(function(k) { return extensionStatus.services[k]; });
            if (activeServices.length > 0) {
                el.textContent = 'Bridge: ON (' + activeServices.join(', ') + ')';
                el.style.background = '#006600';
                el.style.color = '#66ff66';
                bridgeActive = true;
            } else {
                el.textContent = 'Bridge: No active services';
                el.style.background = '#664400';
                el.style.color = '#ffcc66';
                bridgeActive = false;
            }
        }
    }

    // ===== SSE connection =====

    function connectSSE() {
        if (sse) {
            sse.close();
        }
        console.log('[BridgeClient] Connecting to SSE stream');
        sse = new EventSource(BRIDGE_BASE + '/stream');

        sse.addEventListener('proxy-request', function(event) {
            handleProxyRequest(event.data);
        });

        sse.onopen = function() {
            console.log('[BridgeClient] SSE connected');
            updateIndicator();
        };

        sse.onerror = function() {
            console.warn('[BridgeClient] SSE error, reconnecting in 3s');
            sse.close();
            setTimeout(connectSSE, 3000);
        };
    }

    // ===== Proxy request handling =====

    function handleProxyRequest(jsonData) {
        var req;
        try {
            req = JSON.parse(jsonData);
        } catch (e) {
            console.error('[BridgeClient] Failed to parse proxy request:', e);
            return;
        }

        // Try to dispatch through the Chrome Extension first
        if (extensionStatus.installed && extensionStatus.masterEnabled) {
            dispatchViaExtension(req);
        } else {
            // Fall back to direct fetch (server-side validation already happened)
            dispatchViaFetch(req);
        }
    }

    function dispatchViaExtension(req) {
        // Send to the Chrome Extension via postMessage
        var message = {
            type: 'snapagent-proxy-request',
            request: {
                id: req.id,
                url: req.url,
                method: req.method,
                headers: req.headers || {},
                body: req.body
            }
        };

        // Set up a one-time listener for the response
        var responseHandler = function(event) {
            if (event.data && event.data.type === 'snapagent-proxy-response' &&
                event.data.response && event.data.response.id === req.id) {
                window.removeEventListener('message', responseHandler);
                postResult(event.data.response);
            }
        };
        window.addEventListener('message', responseHandler);

        // Timeout: if extension doesn't respond, fall back to direct fetch
        setTimeout(function() {
            window.removeEventListener('message', responseHandler);
            // Check if we already got a response
            // If not, try direct fetch
            console.warn('[BridgeClient] Extension timeout for', req.id, ', falling back to fetch');
            dispatchViaFetch(req);
        }, 25000);

        window.postMessage(message, '*');
    }

    function dispatchViaFetch(req) {
        var init = {
            method: req.method,
            headers: req.headers || {}
        };
        if (req.body && req.method !== 'GET' && req.method !== 'HEAD') {
            init.body = typeof req.body === 'string' ? req.body : JSON.stringify(req.body);
            if (!init.headers['Content-Type']) {
                init.headers['Content-Type'] = 'application/json';
            }
        }
        fetch(req.url, init).then(function(resp) {
            var headers = {};
            resp.headers.forEach(function(val, key) {
                headers[key] = val;
            });
            return resp.text().then(function(body) {
                postResult({
                    id: req.id,
                    status: resp.status,
                    body: body,
                    headers: headers
                });
            });
        }).catch(function(e) {
            postResult({
                id: req.id,
                error: e.message || 'Fetch failed'
            });
        });
    }

    function postResult(result) {
        fetch(BRIDGE_BASE + '/result', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(result)
        }).catch(function(e) {
            console.error('[BridgeClient] Failed to post result:', e);
        });
    }

    // ===== Initialization =====

    function init() {
        updateIndicator();
        connectSSE();
        reportStatus();

        // Poll status every 10s to handle extension install/uninstall
        setInterval(reportStatus, 10000);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    console.log('[BridgeClient] bridge-client.js loaded');
})();
