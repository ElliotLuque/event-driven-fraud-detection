package com.fraud.detection.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class FraudDetectionMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter fraudEventsConsumedCounter;
    private final Timer fraudEvaluationTimer;

    public FraudDetectionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.fraudEventsConsumedCounter = Counter.builder("fraud_events_consumed")
                .description("Total transaction events consumed for fraud evaluation")
                .register(meterRegistry);
        this.fraudEvaluationTimer = Timer.builder("fraud_evaluation_latency")
                .description("Latency of fraud rule evaluation")
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

    public void recordRuleHit(String rule) {
        meterRegistry.counter("fraud_rule_hits", "rule", rule).increment();
    }

    public void recordFraudEventPublished(String outcome) {
        meterRegistry.counter("fraud_events_published", "outcome", outcome).increment();
    }
}
