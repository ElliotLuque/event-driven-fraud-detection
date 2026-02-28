package com.fraud.alert.service;

import com.fraud.alert.model.Alert;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AlertMetrics {

    private final Counter fraudAlertsCreatedCounter;
    private final DistributionSummary fraudAlertRiskScoreSummary;

    public AlertMetrics(MeterRegistry meterRegistry) {
        this.fraudAlertsCreatedCounter = Counter.builder("fraud_alerts")
                .description("Total fraud alerts created by alert-service")
                .register(meterRegistry);
        this.fraudAlertRiskScoreSummary = DistributionSummary.builder("fraud_alert_risk_score")
                .description("Risk score distribution for generated fraud alerts")
                .register(meterRegistry);
    }

    public void recordAlertCreated(Alert alert) {
        fraudAlertsCreatedCounter.increment();
        fraudAlertRiskScoreSummary.record(alert.getRiskScore());
    }
}
