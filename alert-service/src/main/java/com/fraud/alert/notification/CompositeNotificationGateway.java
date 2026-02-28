package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;
import com.fraud.alert.service.NotificationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Dispatches fraud alert notifications to all registered channels.
 * If a channel fails, it logs the error and continues with the remaining channels.
 */
public class CompositeNotificationGateway implements NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(CompositeNotificationGateway.class);
    private final List<NotificationChannel> channels;

    public CompositeNotificationGateway(List<NotificationChannel> channels) {
        this.channels = channels;
        log.info("Notification gateway initialized with channels: {}",
                channels.stream().map(NotificationChannel::channelName).toList());
    }

    @Override
    public void notifyFraud(Alert alert) {
        for (NotificationChannel channel : channels) {
            try {
                channel.send(alert);
            } catch (Exception ex) {
                log.error("Notification channel '{}' failed for alert {}: {}",
                        channel.channelName(), alert.getId(), ex.getMessage(), ex);
            }
        }
    }
}
