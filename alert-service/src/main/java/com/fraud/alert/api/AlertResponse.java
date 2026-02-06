package com.fraud.alert.api;

import java.time.Instant;
import java.util.List;

public record AlertResponse(
        String id,
        String transactionId,
        String userId,
        int riskScore,
        List<String> reasons,
        Instant createdAt
) {
}
