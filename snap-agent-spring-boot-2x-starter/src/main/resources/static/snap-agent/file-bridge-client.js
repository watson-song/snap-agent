/**
 * SnapAgent File Bridge Client
 *
 * Connects to the file bridge SSE endpoint and handles file read requests
 * from the server. Forwards requests to the Chrome extension for local
 * file reading.
 *
 * Usage: Include in HTML when snapAgentBridgeEnabled is true.
 * <script src="/snap-agent/file-bridge-client.js"></script>
 */
(function() {
    'use strict';

    var FileBridgeClient = function() {
        this.eventSource = null;
        this.connected = false;
        this.reconnectDelay = 1000;
    };

    FileBridgeClient.prototype.connect = function() {
        var self = this;
        var url = '/snap-agent/bridge/file/stream';

        console.log('[File Bridge] Connecting to:', url);

        this.eventSource = new EventSource(url);

        this.eventSource.onopen = function() {
            console.log('[File Bridge] Connected');
            self.connected = true;
            self.reconnectDelay = 1000;
        };

        this.eventSource.onerror = function(e) {
            console.error('[File Bridge] Connection error:', e);
            self.connected = false;
            self.eventSource.close();
            setTimeout(function() {
                self.connect();
            }, self.reconnectDelay);
            self.reconnectDelay = Math.min(self.reconnectDelay * 2, 30000);
        };

        this.eventSource.addEventListener('file-read', function(event) {
            try {
                var data = JSON.parse(event.data);
                console.log('[File Bridge] Received file-read request:', data.id);
                self.handleFileReadRequest(data);
            } catch (e) {
                console.error('[File Bridge] Parse error:', e);
            }
        });
    };

    FileBridgeClient.prototype.handleFileReadRequest = function(data) {
        var self = this;
        var requestId = data.id;
        var filePath = data.filePath;

        // Check if Chrome extension is available
        if (typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.sendMessage) {
            // Forward to Chrome extension
            chrome.runtime.sendMessage(
                chrome.runtime.id,
                { type: 'snapagent-read-file', filePath: filePath },
                function(response) {
                    if (chrome.runtime.lastError) {
                        self.sendError(requestId, 'Extension error: ' + chrome.runtime.lastError.message);
                        return;
                    }
                    if (response && response.error) {
                        self.sendError(requestId, response.error);
                        return;
                    }
                    if (response && response.content) {
                        self.sendResult(requestId, response.content);
                    } else {
                        self.sendError(requestId, 'Empty response from extension');
                    }
                }
            );
        } else {
            // No extension - fallback to postMessage for simulator
            console.log('[File Bridge] No extension, forwarding via postMessage');
            window.postMessage({
                type: 'snapagent-read-file',
                requestId: requestId,
                filePath: filePath
            }, '*');
        }
    };

    FileBridgeClient.prototype.sendResult = function(requestId, content) {
        fetch('/snap-agent/bridge/file/result', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                id: requestId,
                content: content,
                error: null
            })
        }).then(function(r) {
            console.log('[File Bridge] Result sent:', requestId, r.status);
        }).catch(function(e) {
            console.error('[File Bridge] Send result error:', e);
        });
    };

    FileBridgeClient.prototype.sendError = function(requestId, error) {
        fetch('/snap-agent/bridge/file/result', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                id: requestId,
                content: null,
                error: error
            })
        }).then(function(r) {
            console.log('[File Bridge] Error sent:', requestId);
        }).catch(function(e) {
            console.error('[File Bridge] Send error error:', e);
        });
    };

    FileBridgeClient.prototype.disconnect = function() {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
        this.connected = false;
    };

    // Auto-initialize when bridge is enabled
    if (window.snapAgentBridgeEnabled) {
        var client = new FileBridgeClient();
        client.connect();
        window.fileBridgeClient = client;
    }

    // Export for manual initialization
    window.SnapAgentFileBridgeClient = FileBridgeClient;
})();
