package com.fraud.alert.service;

import com.fraud.alert.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);

    public void notifyFraud(Alert alert) {
        log.warn(
                "Sending FRAUD alert -> userId={}, transactionId={}, riskScore={}, reasons={}",
                alert.getUserId(),
                alert.getTransactionId(),
                alert.getRiskScore(),
                alert.getReasons()
        );
    }
}
