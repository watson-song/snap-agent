package cn.watsontech.snapagent.boot2x.issue;

import java.util.Collections;
import java.util.Map;

/**
 * Snapshot of the browser extension's state, reported by the frontend via
 * {@code POST /bridge/status-update}.
 *
 * <p>Immutable; {@link IssueBridgeService} stores a volatile reference to
 * an instance of this class for lock-free reads.</p>
 */
public class BridgeClientStatus {

    private final boolean installed;
    private final boolean masterEnabled;
    private final Map<String, Boolean> services;

    public BridgeClientStatus(boolean installed, boolean masterEnabled,
                               Map<String, Boolean> services) {
        this.installed = installed;
        this.masterEnabled = masterEnabled;
        this.services = services != null
                ? Collections.unmodifiableMap(services)
                : Collections.<String, Boolean>emptyMap();
    }

    public boolean isInstalled() {
        return installed;
    }

    public boolean isMasterEnabled() {
        return masterEnabled;
    }

    public Map<String, Boolean> getServices() {
        return services;
    }

    public static BridgeClientStatus inactive() {
        return new BridgeClientStatus(false, false, Collections.<String, Boolean>emptyMap());
    }
}
