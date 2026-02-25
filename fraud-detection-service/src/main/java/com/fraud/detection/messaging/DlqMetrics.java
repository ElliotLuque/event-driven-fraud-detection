package com.fraud.detection.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DlqMetrics {

    private final Counter dlqEventsReceivedCounter;
    private final Counter dlqEventsReprocessedCounter;
    private final Counter dlqEventsFailedCounter;

    public DlqMetrics(MeterRegistry meterRegistry) {
        this.dlqEventsReceivedCounter = Counter.builder("kafka_dlq_events_received")
                .description("Total DLQ events received")
                .register(meterRegistry);
        this.dlqEventsReprocessedCounter = Counter.builder("kafka_dlq_events_reprocessed")
                .description("Total DLQ events reprocessed successfully")
                .register(meterRegistry);
        this.dlqEventsFailedCounter = Counter.builder("kafka_dlq_events_failed")
                .description("Total DLQ events that failed reprocessing")
                .register(meterRegistry);
    }

    public void recordReceived() {
        dlqEventsReceivedCounter.increment();
    }

    public void recordReprocessed() {
        dlqEventsReprocessedCounter.increment();
    }

    public void recordFailed() {
        dlqEventsFailedCounter.increment();
    }
}
