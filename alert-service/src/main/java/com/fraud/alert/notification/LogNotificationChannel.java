package com.fraud.alert.notification;

import com.fraud.alert.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notification channel that logs fraud alerts to the application log.
 * Always active as a baseline audit trail.
 */
public class LogNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationChannel.class);

    @Override
    public void send(Alert alert) {
        log.warn("FRAUD ALERT -> userId={}, transactionId={}, riskScore={}, reasons={}, createdAt={}",
                alert.getUserId(),
                alert.getTransactionId(),
                alert.getRiskScore(),
                alert.getReasons(),
                alert.getCreatedAt()
        );
    }

    @Override
    public String channelName() {
        return "log";
    }
}
