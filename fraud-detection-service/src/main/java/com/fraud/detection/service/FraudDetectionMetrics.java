package com.fraud.detection.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class FraudDetectionMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter fraudEventsConsumedCounter;
    private final Timer fraudEvaluationTimer;
    private final Timer cleanPipelineE2eTimer;
    private final AtomicLong inboxBacklogGauge;

    public FraudDetectionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.fraudEventsConsumedCounter = Counter.builder("fraud_events_consumed")
                .description("Total transaction events consumed for fraud evaluation")
                .register(meterRegistry);
        this.fraudEvaluationTimer = Timer.builder("fraud_evaluation_latency")
                .description("Latency of fraud rule evaluation")
                .register(meterRegistry);
        this.cleanPipelineE2eTimer = Timer.builder("fraud_pipeline_e2e_latency")
                .description("End-to-end latency from transaction ingestion to terminal pipeline outcome")
                .tag("path", "clean")
                .register(meterRegistry);
        this.inboxBacklogGauge = new AtomicLong(0);
        Gauge.builder("fraud_inbox_backlog", inboxBacklogGauge, AtomicLong::get)
                .description("Current backlog of inbox events in RECEIVED state")
                .register(meterRegistry);
    }

    public void recordEventConsumed() {
        fraudEventsConsumedCounter.increment();
    }

    public void recordEvaluationNanos(long durationNanos) {
        fraudEvaluationTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordDecision(String decision) {
        meterRegistry.counter("fraud_decisions", "decision", decision).increment();
    }

    public void recordPipelineE2eClean(long durationMs) {
        cleanPipelineE2eTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRuleHit(String rule) {
        meterRegistry.counter("fraud_rule_hits", "rule", rule).increment();
    }

    public void recordFraudEventPublished(String outcome) {
        meterRegistry.counter("fraud_events_published", "outcome", outcome).increment();
    }

    public void recordInboxBacklog(long backlog) {
        inboxBacklogGauge.set(backlog);
    }
}
