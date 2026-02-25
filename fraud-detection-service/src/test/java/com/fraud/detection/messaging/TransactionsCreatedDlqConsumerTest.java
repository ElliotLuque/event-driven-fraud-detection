package com.fraud.detection.messaging;

import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.model.PaymentMethod;
import com.fraud.detection.service.FraudDetectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TransactionsCreatedDlqConsumerTest {

    @Mock
    private FraudDetectionService fraudDetectionService;

    @Mock
    private DlqMetrics dlqMetrics;

    @InjectMocks
    private TransactionsCreatedDlqConsumer consumer;

    @Test
    void consumeShouldReprocessEventWhenProcessingSucceeds() {
        TransactionCreatedEvent event = buildEvent();

        consumer.consume(event);

        verify(dlqMetrics).recordReceived();
        verify(fraudDetectionService).process(event);
        verify(dlqMetrics).recordReprocessed();
    }

    @Test
    void consumeShouldRecordFailureWhenProcessingThrowsException() {
        TransactionCreatedEvent event = buildEvent();
        doThrow(new IllegalStateException("boom")).when(fraudDetectionService).process(event);

        consumer.consume(event);

        verify(dlqMetrics).recordReceived();
        verify(fraudDetectionService).process(event);
        verify(dlqMetrics).recordFailed();
    }

    @Test
    void consumeShouldRecordFailureAndSkipProcessingWhenEventIsNull() {
        consumer.consume(null);

        verify(dlqMetrics).recordReceived();
        verify(dlqMetrics).recordFailed();
        verifyNoInteractions(fraudDetectionService);
    }

    private TransactionCreatedEvent buildEvent() {
        return new TransactionCreatedEvent(
                "evt-1",
                Instant.parse("2026-01-01T10:00:00Z"),
                "tx-1",
                "user-1",
                new BigDecimal("100.00"),
                "USD",
                "MRC-1",
                "US",
                PaymentMethod.CARD
        );
    }
}
