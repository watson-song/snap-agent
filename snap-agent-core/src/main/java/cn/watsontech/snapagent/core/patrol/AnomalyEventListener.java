package cn.watsontech.snapagent.core.patrol;

/**
 * SPI for receiving anomaly events detected during patrol runs.
 */
public interface AnomalyEventListener {

    /**
     * Called when an anomaly event is detected.
     *
     * @param event the anomaly event
     */
    void onEvent(AnomalyEvent event);
}
