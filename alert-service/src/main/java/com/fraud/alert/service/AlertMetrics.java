package com.fraud.alert.service;

import com.fraud.alert.model.Alert;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AlertMetrics {

    private final Counter fraudAlertsCreatedCounter;
    private final DistributionSummary fraudAlertRiskScoreSummary;
    private final MeterRegistry meterRegistry;
    private final Timer notificationLatencyTimer;
    private final Timer fraudPipelineE2eTimer;
    private final AtomicLong inboxBacklogGauge;

    public AlertMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.fraudAlertsCreatedCounter = Counter.builder("fraud_alerts")
                .description("Total fraud alerts created by alert-service")
                .register(meterRegistry);
        this.fraudAlertRiskScoreSummary = DistributionSummary.builder("fraud_alert_risk_score")
                .description("Risk score distribution for generated fraud alerts")
                .register(meterRegistry);
        this.notificationLatencyTimer = Timer.builder("fraud_alert_notification_latency")
                .description("Latency for sending fraud notifications by channel")
                .register(meterRegistry);
        this.fraudPipelineE2eTimer = Timer.builder("fraud_pipeline_e2e_latency")
                .description("End-to-end latency from transaction ingestion to terminal pipeline outcome")
                .tag("path", "fraud")
                .register(meterRegistry);
        this.inboxBacklogGauge = new AtomicLong(0);
        Gauge.builder("alert_inbox_backlog", inboxBacklogGauge, AtomicLong::get)
                .description("Current backlog of alert inbox events in RECEIVED state")
                .register(meterRegistry);
    }

    public void recordAlertCreated(Alert alert) {
        fraudAlertsCreatedCounter.increment();
        fraudAlertRiskScoreSummary.record(alert.getRiskScore());
        meterRegistry.counter("fraud_alerts_by_severity", "severity", severity(alert.getRiskScore())).increment();
    }

    public void recordNotification(String channel, String outcome, long durationMs) {
        meterRegistry.counter("fraud_alert_notifications", "channel", channel, "outcome", outcome).increment();
        notificationLatencyTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordPipelineE2eFraud(long durationMs) {
        fraudPipelineE2eTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordInboxBacklog(long backlog) {
        inboxBacklogGauge.set(backlog);
    }

    private static String severity(int riskScore) {
        if (riskScore >= 80) {
            return "critical";
        }
        if (riskScore >= 60) {
            return "high";
        }
        if (riskScore >= 40) {
            return "medium";
        }
        return "low";
    }
}
