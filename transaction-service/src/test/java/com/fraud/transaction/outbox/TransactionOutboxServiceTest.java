package com.fraud.transaction.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.transaction.domain.PaymentMethod;
import com.fraud.transaction.events.TransactionCreatedEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionOutboxServiceTest {

    @Mock
    private TransactionOutboxRepository transactionOutboxRepository;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void enqueueShouldPersistTraceparentFromEventTraceAndMdcSpan() {
        TransactionOutboxService outboxService = new TransactionOutboxService(
                transactionOutboxRepository,
                objectMapper,
                tracer,
                "transactions.created"
        );
        TransactionCreatedEvent event = sampleEvent();
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
        when(traceContext.spanId()).thenReturn("0123456789abcdef");
        when(traceContext.sampled()).thenReturn(true);

        outboxService.enqueue(event);

        ArgumentCaptor<TransactionOutboxEvent> captor = ArgumentCaptor.forClass(TransactionOutboxEvent.class);
        verify(transactionOutboxRepository).save(captor.capture());
        TransactionOutboxEvent saved = captor.getValue();

        assertNotNull(saved.getId());
        assertEquals(event.eventId(), saved.getEventId());
        assertEquals("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", saved.getTraceParent());
    }

    @Test
    void enqueueShouldPersistTraceparentFromEventTraceWithoutSpanContext() {
        TransactionOutboxService outboxService = new TransactionOutboxService(
                transactionOutboxRepository,
                objectMapper,
                tracer,
                "transactions.created"
        );
        when(tracer.currentSpan()).thenReturn(null);

        outboxService.enqueue(sampleEvent());

        ArgumentCaptor<TransactionOutboxEvent> captor = ArgumentCaptor.forClass(TransactionOutboxEvent.class);
        verify(transactionOutboxRepository).save(captor.capture());
        String traceParent = captor.getValue().getTraceParent();
        assertNotNull(traceParent);
        assertTrue(traceParent.matches("00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-01"));
    }

    private TransactionCreatedEvent sampleEvent() {
        return new TransactionCreatedEvent(
                "event-1",
                Instant.parse("2026-03-04T20:00:00Z"),
                "tx-1",
                "0123456789abcdef0123456789abcdef",
                "user-1",
                new BigDecimal("45.00"),
                "USD",
                "MRC-1",
                "US",
                PaymentMethod.CARD
        );
    }
}
