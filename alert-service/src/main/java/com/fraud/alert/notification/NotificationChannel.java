package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;

/**
 * A single notification channel capable of delivering fraud alerts.
 * Implementations handle the specifics of each delivery mechanism (log, email, etc.).
 */
public interface NotificationChannel {

    /**
     * Send a fraud alert notification through this channel.
     *
     * @param alert the alert to send
     */
    void send(Alert alert);

    /**
     * @return human-readable name of this channel (for logging)
     */
    String channelName();
}
