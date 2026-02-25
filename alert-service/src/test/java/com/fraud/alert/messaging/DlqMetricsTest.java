package com.fraud.alert.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DlqMetricsTest {

    @Test
    void recordMethodsShouldIncrementDlqCounters() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DlqMetrics metrics = new DlqMetrics(meterRegistry);

        metrics.recordReceived();
        metrics.recordReprocessed();
        metrics.recordFailed();

        assertEquals(1.0, meterRegistry.counter("kafka_dlq_events_received").count(), 0.00001);
        assertEquals(1.0, meterRegistry.counter("kafka_dlq_events_reprocessed").count(), 0.00001);
        assertEquals(1.0, meterRegistry.counter("kafka_dlq_events_failed").count(), 0.00001);
    }
}
