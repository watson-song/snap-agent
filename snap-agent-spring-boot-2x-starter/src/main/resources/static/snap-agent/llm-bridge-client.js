(function() {
    'use strict';

    var LlmBridgeClient = function() {
        this.eventSource = null;
        this.connected = false;
        this.reconnectDelay = 1000;
    };

    LlmBridgeClient.prototype.connect = function() {
        var self = this;
        var url = '/snap-agent/bridge/llm/stream';
        
        console.log('[LLM Bridge] Connecting to:', url);
        
        this.eventSource = new EventSource(url);
        
        this.eventSource.onopen = function() {
            console.log('[LLM Bridge] Connected');
            self.connected = true;
            self.reconnectDelay = 1000;
        };
        
        this.eventSource.onerror = function(e) {
            console.error('[LLM Bridge] Error:', e);
            self.connected = false;
            self.eventSource.close();
            setTimeout(function() {
                self.connect();
            }, self.reconnectDelay);
            self.reconnectDelay = Math.min(self.reconnectDelay * 2, 30000);
        };
        
        this.eventSource.addEventListener('llm-request', function(event) {
            try {
                var data = JSON.parse(event.data);
                console.log('[LLM Bridge] Received request:', data.id);
                self.handleLlmRequest(data);
            } catch (e) {
                console.error('[LLM Bridge] Parse error:', e);
            }
        });
    };

    LlmBridgeClient.prototype.handleLlmRequest = function(data) {
        var self = this;
        var taskId = data.id;
        var request = data.request;
        
        // Check if Chrome extension is available
        if (typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.sendMessage) {
            // Use Chrome extension
            chrome.runtime.sendMessage(
                chrome.runtime.id,
                { type: 'snapagent-llm-request', request: { id: taskId, domain: window.location.origin, messages: request.messages, system: request.systemPrompt, tools: request.tools, model: request.model } },
                function(response) {
                    if (chrome.runtime.lastError) {
                        self.sendError(taskId, 'Extension error: ' + chrome.runtime.lastError.message);
                        return;
                    }
                    if (response && response.error) {
                        self.sendError(taskId, response.error);
                        return;
                    }
                    self.sendResult(taskId, response);
                }
            );
        } else {
            // No extension - forward via postMessage for simulator
            console.log('[LLM Bridge] No extension, forwarding via postMessage');
            window.postMessage({
                type: 'snapagent-llm-request',
                request: {
                    id: taskId,
                    domain: window.location.origin,
                    messages: request.messages,
                    system: request.systemPrompt,
                    tools: request.tools,
                    model: request.model
                }
            }, '*');
        }
    };

    LlmBridgeClient.prototype.sendResult = function(taskId, response) {
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
            console.log('[LLM Bridge] Result sent:', taskId, r.status);
        }).catch(function(e) {
            console.error('[LLM Bridge] Send result error:', e);
        });
    };

    LlmBridgeClient.prototype.sendError = function(taskId, error) {
        fetch('/snap-agent/bridge/llm/error', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: taskId, error: error })
        }).then(function(r) {
            console.log('[LLM Bridge] Error sent:', taskId);
        }).catch(function(e) {
            console.error('[LLM Bridge] Send error error:', e);
        });
    };

    LlmBridgeClient.prototype.disconnect = function() {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
        this.connected = false;
    };

    // Auto-initialize
    if (window.snapAgentBridgeEnabled) {
        var client = new LlmBridgeClient();
        client.connect();
        window.llmBridgeClient = client;
    }

    window.SnapAgentLlmBridgeClient = LlmBridgeClient;
})();
