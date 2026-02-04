package com.fraud.detection.events;

import java.time.Instant;
import java.util.List;

public record FraudDetectedEvent(
        String eventId,
        Instant occurredAt,
        String transactionId,
        String userId,
        int riskScore,
        List<String> reasons,
        String ruleVersion
) {
}
