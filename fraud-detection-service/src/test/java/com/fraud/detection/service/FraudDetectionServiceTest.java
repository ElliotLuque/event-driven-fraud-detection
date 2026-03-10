package com.fraud.detection.service;

import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.events.FraudDetectedEvent;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.inbox.FraudInboxEvent;
import com.fraud.detection.inbox.FraudInboxRepository;
import com.fraud.detection.mapping.FraudDetectedEventMapper;
import com.fraud.detection.mapping.UserTransactionHistoryMapper;
import com.fraud.detection.model.PaymentMethod;
import com.fraud.detection.model.UserTransactionHistory;
import com.fraud.detection.outbox.FraudOutboxService;
import com.fraud.detection.repository.UserTransactionHistoryRepository;
import com.fraud.detection.rules.FraudEvaluation;
import com.fraud.detection.rules.FraudRulesEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock
    private FraudInboxRepository fraudInboxRepository;

    @Mock
    private UserTransactionHistoryRepository historyRepository;

    @Mock
    private FraudRulesEngine fraudRulesEngine;

    @Mock
    private FraudOutboxService fraudOutboxService;

    @Mock
    private FraudDetectionMetrics fraudDetectionMetrics;

    private FraudRulesProperties rules;
    private FraudDetectionService fraudDetectionService;
    private UserTransactionHistoryMapper userTransactionHistoryMapper;
    private FraudDetectedEventMapper fraudDetectedEventMapper;

    @BeforeEach
    void setUp() {
        rules = new FraudRulesProperties();
        rules.setVelocityWindow(Duration.ofMinutes(1));
        userTransactionHistoryMapper = Mappers.getMapper(UserTransactionHistoryMapper.class);
        fraudDetectedEventMapper = Mappers.getMapper(FraudDetectedEventMapper.class);
        fraudDetectionService = new FraudDetectionService(
                fraudInboxRepository,
                historyRepository,
                fraudRulesEngine,
                fraudOutboxService,
                rules,
                userTransactionHistoryMapper,
                fraudDetectedEventMapper,
                fraudDetectionMetrics
        );
    }

    @Test
    void processShouldSkipWhenEventWasAlreadyProcessed() {
        TransactionCreatedEvent event = buildEvent("evt-1", Instant.parse("2026-01-01T10:00:00Z"));
        when(fraudInboxRepository.insertReceivedIfAbsent(eq(event.eventId()), eq(event.transactionId()), eq(event.traceId()), eq("INLINE_PROCESSING"), any(Instant.class), any(Instant.class)))
                .thenReturn(0);
        when(fraudInboxRepository.lockByEventId(event.eventId()))
                .thenReturn(Optional.of(buildInboxEvent(event, Instant.parse("2026-01-01T10:00:00Z"), true)));

        fraudDetectionService.process(event);

        verify(fraudInboxRepository).insertReceivedIfAbsent(eq(event.eventId()), eq(event.transactionId()), eq(event.traceId()), eq("INLINE_PROCESSING"), any(Instant.class), any(Instant.class));
        verify(fraudInboxRepository).lockByEventId(event.eventId());
        verify(fraudInboxRepository, never()).markProcessed(any(), any());
        verifyNoInteractions(historyRepository, fraudRulesEngine, fraudOutboxService);
    }

    @Test
    void processShouldPersistHistoryAndProcessedMarkerWhenNotFraudulent() {
        Instant occurredAt = Instant.parse("2026-01-01T10:00:00Z");
        TransactionCreatedEvent event = buildEvent("evt-2", occurredAt);

        when(historyRepository.countByUserIdAndOccurredAtAfter(eq("user-1"), eq(occurredAt.minus(Duration.ofMinutes(1)))))
                .thenReturn(2L);
        when(historyRepository.findTopByUserIdOrderByOccurredAtDesc("user-1")).thenReturn(Optional.empty());
        when(fraudRulesEngine.evaluate(event, Optional.empty(), 2L, occurredAt))
                .thenReturn(new FraudEvaluation(false, 0, List.of()));
        when(fraudInboxRepository.insertReceivedIfAbsent(eq(event.eventId()), eq(event.transactionId()), eq(event.traceId()), eq("INLINE_PROCESSING"), eq(occurredAt), any(Instant.class)))
                .thenReturn(1);
        when(fraudInboxRepository.markProcessed(eq(event.eventId()), any(Instant.class))).thenReturn(1);

        fraudDetectionService.process(event);

        ArgumentCaptor<UserTransactionHistory> historyCaptor = ArgumentCaptor.forClass(UserTransactionHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        UserTransactionHistory storedHistory = historyCaptor.getValue();

        verify(fraudInboxRepository).insertReceivedIfAbsent(eq(event.eventId()), eq(event.transactionId()), eq(event.traceId()), eq("INLINE_PROCESSING"), eq(occurredAt), any(Instant.class));
        verify(fraudInboxRepository).markProcessed(eq(event.eventId()), any(Instant.class));

        verify(fraudRulesEngine).evaluate(event, Optional.empty(), 2L, occurredAt);
        verify(fraudOutboxService, never()).enqueue(any(FraudDetectedEvent.class));

        assertEquals(event.transactionId(), storedHistory.getTransactionId());
        assertEquals(event.userId(), storedHistory.getUserId());
        assertEquals(occurredAt, storedHistory.getOccurredAt());
        verifyNoMoreInteractions(fraudInboxRepository);
    }

    @Test
    void processShouldPublishFraudEventWhenEvaluationIsFraudulent() {
        TransactionCreatedEvent event = buildEvent("evt-3", null);
        Instant before = Instant.now();

        when(historyRepository.countByUserIdAndOccurredAtAfter(eq("user-1"), any(Instant.class))).thenReturn(0L);
        when(historyRepository.findTopByUserIdOrderByOccurredAtDesc("user-1")).thenReturn(Optional.empty());
        when(fraudRulesEngine.evaluate(eq(event), eq(Optional.empty()), eq(0L), any(Instant.class)))
                .thenReturn(new FraudEvaluation(true, 80, List.of("HIGH_AMOUNT", "HIGH_RISK_MERCHANT")));
        when(fraudInboxRepository.insertReceivedIfAbsent(eq(event.eventId()), eq(event.transactionId()), eq(event.traceId()), eq("INLINE_PROCESSING"), any(Instant.class), any(Instant.class)))
                .thenReturn(1);
        when(fraudInboxRepository.markProcessed(eq(event.eventId()), any(Instant.class))).thenReturn(1);

        fraudDetectionService.process(event);

        ArgumentCaptor<UserTransactionHistory> historyCaptor = ArgumentCaptor.forClass(UserTransactionHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        UserTransactionHistory storedHistory = historyCaptor.getValue();
        verify(fraudInboxRepository).insertReceivedIfAbsent(eq(event.eventId()), eq(event.transactionId()), eq(event.traceId()), eq("INLINE_PROCESSING"), any(Instant.class), any(Instant.class));
        verify(fraudInboxRepository).markProcessed(eq(event.eventId()), any(Instant.class));

        ArgumentCaptor<FraudDetectedEvent> fraudEventCaptor = ArgumentCaptor.forClass(FraudDetectedEvent.class);
        verify(fraudOutboxService).enqueue(fraudEventCaptor.capture());
        FraudDetectedEvent fraudEvent = fraudEventCaptor.getValue();

        assertNotNull(storedHistory.getOccurredAt());

        assertEquals(event.transactionId(), fraudEvent.transactionId());
        assertEquals(event.traceId(), fraudEvent.traceId());
        assertEquals(event.userId(), fraudEvent.userId());
        assertEquals(80, fraudEvent.riskScore());
        assertEquals(List.of("HIGH_AMOUNT", "HIGH_RISK_MERCHANT"), fraudEvent.reasons());
        assertEquals("v1.0.0", fraudEvent.ruleVersion());
        assertTrue(fraudEvent.occurredAt().isAfter(before) || fraudEvent.occurredAt().equals(before));
    }

    @Test
    void processShouldPreferEventTraceIdOverMdcTraceId() {
        TransactionCreatedEvent event = buildEvent("evt-4", null);
        MDC.put("traceId", "mdc-trace-that-must-not-win");

        when(historyRepository.countByUserIdAndOccurredAtAfter(eq("user-1"), any(Instant.class))).thenReturn(0L);
        when(historyRepository.findTopByUserIdOrderByOccurredAtDesc("user-1")).thenReturn(Optional.empty());
        when(fraudRulesEngine.evaluate(eq(event), eq(Optional.empty()), eq(0L), any(Instant.class)))
                .thenReturn(new FraudEvaluation(true, 80, List.of("HIGH_AMOUNT")));
        when(fraudInboxRepository.insertReceivedIfAbsent(eq(event.eventId()), eq(event.transactionId()), eq(event.traceId()), eq("INLINE_PROCESSING"), any(Instant.class), any(Instant.class)))
                .thenReturn(1);
        when(fraudInboxRepository.markProcessed(eq(event.eventId()), any(Instant.class))).thenReturn(1);

        try {
            fraudDetectionService.process(event);
        } finally {
            MDC.remove("traceId");
        }

        ArgumentCaptor<FraudDetectedEvent> fraudEventCaptor = ArgumentCaptor.forClass(FraudDetectedEvent.class);
        verify(fraudOutboxService).enqueue(fraudEventCaptor.capture());
        assertEquals(event.traceId(), fraudEventCaptor.getValue().traceId());
    }

    private TransactionCreatedEvent buildEvent(String eventId, Instant occurredAt) {
        return new TransactionCreatedEvent(
                eventId,
                occurredAt,
                "tx-1",
                "0123456789abcdef0123456789abcdef",
                "user-1",
                new BigDecimal("250.00"),
                "USD",
                "MRC-101",
                "US",
                PaymentMethod.CARD
        );
    }

    private FraudInboxEvent buildInboxEvent(TransactionCreatedEvent event, Instant occurredAt, boolean processed) {
        FraudInboxEvent inboxEvent = FraudInboxEvent.received(
                event.eventId(),
                event.transactionId(),
                event.traceId(),
                "INLINE_PROCESSING",
                occurredAt,
                Instant.now()
        );
        if (processed) {
            inboxEvent.markProcessed(Instant.now());
        }
        return inboxEvent;
    }
}
