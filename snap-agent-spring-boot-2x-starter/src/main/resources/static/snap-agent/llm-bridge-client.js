/**
 * SnapAgent LLM Bridge Client
 *
 * Listens for LLM requests from Server via SSE, forwards them to
 * browser extension, and sends responses back to Server.
 */

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
        
        this.eventSource = new EventSource(url);
        
        this.eventSource.onopen = function() {
            console.log('[LLM Bridge] Connected to server');
            self.connected = true;
            self.reconnectDelay = 1000;
        };
        
        this.eventSource.onerror = function(e) {
            console.error('[LLM Bridge] Connection error:', e);
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
                self.handleLlmRequest(data);
            } catch (e) {
                console.error('[LLM Bridge] Failed to parse LLM request:', e);
            }
        });
    };

    LlmBridgeClient.prototype.handleLlmRequest = function(data) {
        var self = this;
        var taskId = data.id;
        var request = data.request;
        
        console.log('[LLM Bridge] Received LLM request:', taskId);
        
        // Check if extension is installed
        chrome.runtime.sendMessage(
            chrome.runtime.id,
            { type: 'snapagent-get-config' },
            function(response) {
                if (chrome.runtime.lastError) {
                    self.sendError(taskId, 'Extension not installed');
                    return;
                }
                
                var config = response || {};
                if (!config.masterEnabled || !config.services || !config.services['llm']) {
                    self.sendError(taskId, 'LLM service is disabled in extension');
                    return;
                }
                
                // Forward to extension
                chrome.runtime.sendMessage(
                    chrome.runtime.id,
                    {
                        type: 'snapagent-llm-request',
                        request: {
                            id: taskId,
                            domain: window.location.origin,
                            messages: request.messages || [],
                            system: request.systemPrompt || '',
                            tools: request.tools || [],
                            model: request.model
                        }
                    },
                    function(llmResponse) {
                        if (chrome.runtime.lastError) {
                            self.sendError(taskId, 'Extension communication failed');
                            return;
                        }
                        
                        if (llmResponse && llmResponse.error) {
                            self.sendError(taskId, llmResponse.error);
                            return;
                        }
                        
                        // Send success response to server
                        self.sendResult(taskId, llmResponse);
                    }
                );
            }
        );
    };

    LlmBridgeClient.prototype.sendResult = function(taskId, response) {
        fetch('/snap-agent/bridge/llm/result', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                id: taskId,
                text: response.text || '',
                toolCalls: response.toolCalls || [],
                usage: response.usage || {
                    inputTokens: 0,
                    outputTokens: 0,
                    cacheReadTokens: 0
                }
            })
        }).then(function(resp) {
            if (resp.ok) {
                console.log('[LLM Bridge] Result sent:', taskId);
            } else {
                console.error('[LLM Bridge] Failed to send result:', taskId);
            }
        }).catch(function(e) {
            console.error('[LLM Bridge] Error sending result:', e);
        });
    };

    LlmBridgeClient.prototype.sendError = function(taskId, error) {
        fetch('/snap-agent/bridge/llm/error', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                id: taskId,
                error: error
            })
        }).then(function(resp) {
            console.log('[LLM Bridge] Error sent:', taskId, error);
        }).catch(function(e) {
            console.error('[LLM Bridge] Error sending error:', e);
        });
    };

    LlmBridgeClient.prototype.disconnect = function() {
        if (this.eventSource) {
            this.eventSource.close();
            this.eventSource = null;
        }
        this.connected = false;
    };

    // Auto-initialize when snapAgentBridgeEnabled is true
    if (window.snapAgentBridgeEnabled) {
        var client = new LlmBridgeClient();
        client.connect();
        window.llmBridgeClient = client;
    }

    // Export for manual initialization
    window.SnapAgentLlmBridgeClient = LlmBridgeClient;
})();
