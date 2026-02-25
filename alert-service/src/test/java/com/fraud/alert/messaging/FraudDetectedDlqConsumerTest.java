package com.fraud.alert.messaging;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.service.AlertProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class FraudDetectedDlqConsumerTest {

    @Mock
    private AlertProcessingService alertProcessingService;

    @Mock
    private DlqMetrics dlqMetrics;

    @InjectMocks
    private FraudDetectedDlqConsumer consumer;

    @Test
    void consumeShouldReprocessEventWhenProcessingSucceeds() {
        FraudDetectedEvent event = buildEvent();

        consumer.consume(event);

        verify(dlqMetrics).recordReceived();
        verify(alertProcessingService).process(event);
        verify(dlqMetrics).recordReprocessed();
    }

    @Test
    void consumeShouldRecordFailureWhenProcessingThrowsException() {
        FraudDetectedEvent event = buildEvent();
        doThrow(new IllegalStateException("boom")).when(alertProcessingService).process(event);

        consumer.consume(event);

        verify(dlqMetrics).recordReceived();
        verify(alertProcessingService).process(event);
        verify(dlqMetrics).recordFailed();
    }

    @Test
    void consumeShouldRecordFailureAndSkipProcessingWhenEventIsNull() {
        consumer.consume(null);

        verify(dlqMetrics).recordReceived();
        verify(dlqMetrics).recordFailed();
        verifyNoInteractions(alertProcessingService);
    }

    private FraudDetectedEvent buildEvent() {
        return new FraudDetectedEvent(
                "evt-1",
                Instant.parse("2026-01-01T10:00:00Z"),
                "tx-1",
                "user-1",
                90,
                List.of("HIGH_AMOUNT"),
                "v1.0.0"
        );
    }
}
