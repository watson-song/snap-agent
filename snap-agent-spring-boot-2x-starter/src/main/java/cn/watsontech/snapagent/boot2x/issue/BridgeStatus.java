package cn.watsontech.snapagent.boot2x.issue;

import java.util.Map;

/**
 * Status snapshot returned by {@code GET /bridge/status}.
 */
public class BridgeStatus {

    private boolean connected;
    private int emitterCount;
    private int pendingCount;
    private boolean installed;
    private boolean masterEnabled;
    private Map<String, Boolean> services;

    public BridgeStatus() {
    }

    public BridgeStatus(boolean connected, int emitterCount, int pendingCount,
                        boolean installed, boolean masterEnabled,
                        Map<String, Boolean> services) {
        this.connected = connected;
        this.emitterCount = emitterCount;
        this.pendingCount = pendingCount;
        this.installed = installed;
        this.masterEnabled = masterEnabled;
        this.services = services;
    }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public int getEmitterCount() { return emitterCount; }
    public void setEmitterCount(int emitterCount) { this.emitterCount = emitterCount; }
    public int getPendingCount() { return pendingCount; }
    public void setPendingCount(int pendingCount) { this.pendingCount = pendingCount; }
    public boolean isInstalled() { return installed; }
    public void setInstalled(boolean installed) { this.installed = installed; }
    public boolean isMasterEnabled() { return masterEnabled; }
    public void setMasterEnabled(boolean masterEnabled) { this.masterEnabled = masterEnabled; }
    public Map<String, Boolean> getServices() { return services; }
    public void setServices(Map<String, Boolean> services) { this.services = services; }
}
