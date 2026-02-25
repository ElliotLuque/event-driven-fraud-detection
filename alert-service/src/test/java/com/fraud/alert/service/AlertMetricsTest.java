package com.fraud.alert.service;

import com.fraud.alert.model.Alert;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertMetricsTest {

    @Test
    void recordAlertCreatedShouldIncrementCountersAndRecordRiskScore() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AlertMetrics alertMetrics = new AlertMetrics(meterRegistry);

        Alert alert = new Alert(
                "a-1",
                "tx-1",
                "user-1",
                73,
                "HIGH_AMOUNT",
                Instant.parse("2026-01-01T10:00:00Z")
        );

        alertMetrics.recordAlertCreated(alert);

        assertEquals(1.0, meterRegistry.counter("fraud_alerts_created").count(), 0.00001);

        DistributionSummary summary = meterRegistry.find("fraud_alert_risk_score").summary();
        assertEquals(1L, summary.count());
        assertEquals(73.0, summary.totalAmount(), 0.00001);
    }
}
