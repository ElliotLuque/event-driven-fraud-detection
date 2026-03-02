package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;
import com.fraud.alert.service.AlertMetrics;
import com.fraud.alert.service.NotificationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Dispatches fraud alert notifications to all registered channels.
 * If a channel fails, it logs the error and continues with the remaining channels.
 */
public class CompositeNotificationGateway implements NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(CompositeNotificationGateway.class);
    private final List<NotificationChannel> channels;
    private final AlertMetrics alertMetrics;

    public CompositeNotificationGateway(List<NotificationChannel> channels, AlertMetrics alertMetrics) {
        this.channels = channels;
        this.alertMetrics = alertMetrics;
        log.info("notification_gateway_initialized",
                kv("event", "notification_gateway_initialized"),
                kv("outcome", "success"),
                kv("channels", channels.stream().map(NotificationChannel::channelName).toList())
        );
    }

    @Override
    public void notifyFraud(Alert alert) {
        for (NotificationChannel channel : channels) {
            long attemptStartNanos = System.nanoTime();
            String channelName = channel.channelName();
            log.info("notification_attempt",
                    kv("event", "notification_attempt"),
                    kv("outcome", "attempt"),
                    kv("alertId", alert.getId()),
                    kv("transactionId", alert.getTransactionId()),
                    kv("channel", channelName)
            );
            try {
                channel.send(alert);
                long durationMs = (System.nanoTime() - attemptStartNanos) / 1_000_000;
                alertMetrics.recordNotification(channelName, "success", durationMs);
                log.info("notification_result",
                        kv("event", "notification_result"),
                        kv("outcome", "success"),
                        kv("alertId", alert.getId()),
                        kv("transactionId", alert.getTransactionId()),
                        kv("channel", channelName),
                        kv("duration_ms", durationMs)
                );
            } catch (Exception ex) {
                long durationMs = (System.nanoTime() - attemptStartNanos) / 1_000_000;
                alertMetrics.recordNotification(channelName, "failed", durationMs);
                log.error("notification_result",
                        kv("event", "notification_result"),
                        kv("outcome", "failed"),
                        kv("alertId", alert.getId()),
                        kv("transactionId", alert.getTransactionId()),
                        kv("channel", channelName),
                        kv("duration_ms", durationMs),
                        kv("error_code", "NOTIFICATION_CHANNEL_FAILED"),
                        kv("error_class", ex.getClass().getSimpleName()),
                        ex
                );
            }
        }
    }
}
