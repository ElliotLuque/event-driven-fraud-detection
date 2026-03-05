package com.fraud.transaction.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.transaction.domain.PaymentMethod;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.messaging.TransactionEventPublisher;
import com.fraud.transaction.service.TransactionMetrics;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    @Mock
    private Span.Builder spanBuilder;

    @Mock
    private Span span;

    @Mock
    private Tracer.SpanInScope spanInScope;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void relayPendingEventsShouldPublishAndMarkAsPublished() throws Exception {
        TransactionCreatedEvent event = sampleEvent();
        TransactionOutboxEvent outboxEvent = TransactionOutboxEvent.pending(
                "outbox-1",
                event.eventId(),
                "transactions.created",
                event.transactionId(),
                objectMapper.valueToTree(event),
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                null,
                Instant.now()
        );

        when(transactionOutboxRepository.lockPendingBatch(any(Instant.class), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(transactionEventPublisher.publishAsync(
                eq("transactions.created"),
                eq(event.transactionId()),
                any(TransactionCreatedEvent.class)
        )).thenReturn(CompletableFuture.completedFuture(null));
        mockTraceScope();

        TransactionOutboxRelayService relayService = new TransactionOutboxRelayService(
                transactionOutboxRepository,
                transactionEventPublisher,
                transactionMetrics,
                objectMapper,
                tracer,
                propagator,
                50,
                Duration.ofSeconds(2),
                Duration.ofHours(24)
        );

        relayService.relayPendingEvents();

        ArgumentCaptor<TransactionCreatedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCreatedEvent.class);
        verify(transactionEventPublisher).publishAsync(
                eq("transactions.created"),
                eq(event.transactionId()),
                eventCaptor.capture()
        );
        TransactionCreatedEvent publishedEvent = eventCaptor.getValue();
        assertEquals(event.eventId(), publishedEvent.eventId());
        assertEquals(event.transactionId(), publishedEvent.transactionId());
        assertEquals(event.traceId(), publishedEvent.traceId());
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
                objectMapper.valueToTree(event),
                null,
                null,
                Instant.now()
        );

        when(transactionOutboxRepository.lockPendingBatch(any(Instant.class), eq(25)))
                .thenReturn(List.of(outboxEvent));
        when(transactionEventPublisher.publishAsync(
                eq("transactions.created"),
                eq(event.transactionId()),
                any(TransactionCreatedEvent.class)
        )).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        TransactionOutboxRelayService relayService = new TransactionOutboxRelayService(
                transactionOutboxRepository,
                transactionEventPublisher,
                transactionMetrics,
                objectMapper,
                tracer,
                propagator,
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

    @Test
    void relayPendingEventsShouldBuildTraceParentWhenMissingInOutbox() throws Exception {
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                "event-2",
                Instant.parse("2026-03-04T20:01:00Z"),
                "tx-2",
                "0123456789abcdef0123456789abcdef",
                "user-1",
                new BigDecimal("45.00"),
                "USD",
                "MRC-1",
                "US",
                PaymentMethod.CARD
        );
        TransactionOutboxEvent outboxEvent = TransactionOutboxEvent.pending(
                "outbox-3",
                event.eventId(),
                "transactions.created",
                event.transactionId(),
                objectMapper.valueToTree(event),
                null,
                null,
                Instant.now()
        );

        when(transactionOutboxRepository.lockPendingBatch(any(Instant.class), eq(10)))
                .thenReturn(List.of(outboxEvent));
        when(transactionEventPublisher.publishAsync(
                eq("transactions.created"),
                eq(event.transactionId()),
                any(TransactionCreatedEvent.class)
        )).thenReturn(CompletableFuture.completedFuture(null));
        mockTraceScope();

        TransactionOutboxRelayService relayService = new TransactionOutboxRelayService(
                transactionOutboxRepository,
                transactionEventPublisher,
                transactionMetrics,
                objectMapper,
                tracer,
                propagator,
                10,
                Duration.ofSeconds(2),
                Duration.ofHours(24)
        );

        relayService.relayPendingEvents();

        ArgumentCaptor<java.util.Map<String, String>> headersCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(transactionEventPublisher).publishAsync(
                eq("transactions.created"),
                eq(event.transactionId()),
                any(TransactionCreatedEvent.class)
        );
        verify(propagator).extract(headersCaptor.capture(), any());
        assertTrue(headersCaptor.getValue().get("traceparent").matches("00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-01"));
    }

    private void mockTraceScope() {
        when(propagator.extract(anyMap(), any())).thenReturn(spanBuilder);
        when(spanBuilder.name(any())).thenReturn(spanBuilder);
        when(spanBuilder.kind(any())).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(spanInScope);
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
