package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Notification channel that logs fraud alerts to the application log.
 * Always active as a baseline audit trail.
 */
public class LogNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationChannel.class);

    @Override
    public void send(Alert alert) {
        log.warn("FRAUD ALERT",
                kv("event", "fraud_alert_logged"),
                kv("outcome", "success"),
                kv("channel", "log"),
                kv("alertId", alert.getId()),
                kv("transactionId", alert.getTransactionId()),
                kv("user_hash", Integer.toHexString(alert.getUserId().hashCode())),
                kv("risk_score", alert.getRiskScore()),
                kv("reasons", alert.getReasons()),
                kv("createdAt", alert.getCreatedAt())
        );
    }

    @Override
    public String channelName() {
        return "log";
    }
}
