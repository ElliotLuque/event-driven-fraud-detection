package com.fraud.transaction.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.transaction.domain.PaymentMethod;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.messaging.TransactionEventPublisher;
import com.fraud.transaction.service.TransactionMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionOutboxRelayServiceTest {

    @Mock
    private TransactionOutboxRepository transactionOutboxRepository;

    @Mock
    private TransactionEventPublisher transactionEventPublisher;

    @Mock
    private TransactionMetrics transactionMetrics;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void relayPendingEventsShouldPublishAndMarkAsPublished() throws Exception {
        TransactionCreatedEvent event = sampleEvent();
        TransactionOutboxEvent outboxEvent = TransactionOutboxEvent.pending(
                "outbox-1",
                event.eventId(),
                "transactions.created",
                event.transactionId(),
                objectMapper.writeValueAsString(event),
                Instant.now()
        );

        when(transactionOutboxRepository.lockPendingBatch(any(Instant.class), eq(50)))
                .thenReturn(List.of(outboxEvent));

        TransactionOutboxRelayService relayService = new TransactionOutboxRelayService(
                transactionOutboxRepository,
                transactionEventPublisher,
                transactionMetrics,
                objectMapper,
                50,
                Duration.ofSeconds(2),
                Duration.ofHours(24)
        );

        relayService.relayPendingEvents();

        verify(transactionEventPublisher).publish("transactions.created", event.transactionId(), event);
        verify(transactionMetrics).recordTransactionEventPublished(eq("success"), anyLong());
        assertEquals(TransactionOutboxStatus.PUBLISHED, outboxEvent.getStatus());
        assertNotNull(outboxEvent.getPublishedAt());
    }

    @Test
    void relayPendingEventsShouldKeepEventPendingWhenPublishFails() throws Exception {
        TransactionCreatedEvent event = sampleEvent();
        TransactionOutboxEvent outboxEvent = TransactionOutboxEvent.pending(
                "outbox-2",
                event.eventId(),
                "transactions.created",
                event.transactionId(),
                objectMapper.writeValueAsString(event),
                Instant.now()
        );

        when(transactionOutboxRepository.lockPendingBatch(any(Instant.class), eq(25)))
                .thenReturn(List.of(outboxEvent));
        doThrow(new IllegalStateException("Kafka unavailable")).when(transactionEventPublisher)
                .publish(eq("transactions.created"), eq(event.transactionId()), eq(event));

        TransactionOutboxRelayService relayService = new TransactionOutboxRelayService(
                transactionOutboxRepository,
                transactionEventPublisher,
                transactionMetrics,
                objectMapper,
                25,
                Duration.ofSeconds(5),
                Duration.ofHours(24)
        );

        Instant beforeRun = Instant.now();
        relayService.relayPendingEvents();

        verify(transactionMetrics).recordTransactionEventPublished(eq("failed"), anyLong());
        assertEquals(TransactionOutboxStatus.PENDING, outboxEvent.getStatus());
        assertEquals(1, outboxEvent.getAttempts());
        assertTrue(outboxEvent.getNextAttemptAt().isAfter(beforeRun));
    }

    private TransactionCreatedEvent sampleEvent() {
        return new TransactionCreatedEvent(
                "event-1",
                Instant.parse("2026-03-04T20:00:00Z"),
                "tx-1",
                "trace-1",
                "user-1",
                new BigDecimal("45.00"),
                "USD",
                "MRC-1",
                "US",
                PaymentMethod.CARD
        );
    }
}
