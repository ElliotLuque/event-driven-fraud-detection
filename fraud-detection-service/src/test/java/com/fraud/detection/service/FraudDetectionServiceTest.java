package com.fraud.detection.service;

import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.events.FraudDetectedEvent;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.mapping.FraudDetectedEventMapper;
import com.fraud.detection.mapping.UserTransactionHistoryMapper;
import com.fraud.detection.messaging.FraudEventPublisher;
import com.fraud.detection.model.PaymentMethod;
import com.fraud.detection.model.ProcessedEvent;
import com.fraud.detection.model.UserTransactionHistory;
import com.fraud.detection.repository.ProcessedEventRepository;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private UserTransactionHistoryRepository historyRepository;

    @Mock
    private FraudRulesEngine fraudRulesEngine;

    @Mock
    private FraudEventPublisher fraudEventPublisher;

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
                processedEventRepository,
                historyRepository,
                fraudRulesEngine,
                fraudEventPublisher,
                rules,
                userTransactionHistoryMapper,
                fraudDetectedEventMapper
        );
    }

    @Test
    void processShouldSkipWhenEventWasAlreadyProcessed() {
        TransactionCreatedEvent event = buildEvent("evt-1", Instant.parse("2026-01-01T10:00:00Z"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        fraudDetectionService.process(event);

        verify(processedEventRepository).existsById("evt-1");
        verifyNoInteractions(historyRepository, fraudRulesEngine, fraudEventPublisher);
        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
    }

    @Test
    void processShouldPersistHistoryAndProcessedMarkerWhenNotFraudulent() {
        Instant occurredAt = Instant.parse("2026-01-01T10:00:00Z");
        TransactionCreatedEvent event = buildEvent("evt-2", occurredAt);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(historyRepository.countByUserIdAndOccurredAtAfter(eq("user-1"), eq(occurredAt.minus(Duration.ofMinutes(1)))))
                .thenReturn(2L);
        when(historyRepository.findTopByUserIdOrderByOccurredAtDesc("user-1")).thenReturn(Optional.empty());
        when(fraudRulesEngine.evaluate(event, Optional.empty(), 2L, occurredAt))
                .thenReturn(new FraudEvaluation(false, 0, List.of()));

        fraudDetectionService.process(event);

        ArgumentCaptor<UserTransactionHistory> historyCaptor = ArgumentCaptor.forClass(UserTransactionHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        UserTransactionHistory storedHistory = historyCaptor.getValue();

        ArgumentCaptor<ProcessedEvent> processedCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processedCaptor.capture());
        ProcessedEvent processedEvent = processedCaptor.getValue();

        verify(fraudRulesEngine).evaluate(event, Optional.empty(), 2L, occurredAt);
        verify(fraudEventPublisher, never()).publish(any(FraudDetectedEvent.class));

        assertEquals(event.transactionId(), storedHistory.getTransactionId());
        assertEquals(event.userId(), storedHistory.getUserId());
        assertEquals(occurredAt, storedHistory.getOccurredAt());

        assertEquals(event.eventId(), processedEvent.getEventId());
        assertEquals(occurredAt, processedEvent.getProcessedAt());
    }

    @Test
    void processShouldPublishFraudEventWhenEvaluationIsFraudulent() {
        TransactionCreatedEvent event = buildEvent("evt-3", null);
        Instant before = Instant.now();

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(historyRepository.countByUserIdAndOccurredAtAfter(eq("user-1"), any(Instant.class))).thenReturn(0L);
        when(historyRepository.findTopByUserIdOrderByOccurredAtDesc("user-1")).thenReturn(Optional.empty());
        when(fraudRulesEngine.evaluate(eq(event), eq(Optional.empty()), eq(0L), any(Instant.class)))
                .thenReturn(new FraudEvaluation(true, 80, List.of("HIGH_AMOUNT", "HIGH_RISK_MERCHANT")));

        fraudDetectionService.process(event);
        Instant after = Instant.now();

        ArgumentCaptor<UserTransactionHistory> historyCaptor = ArgumentCaptor.forClass(UserTransactionHistory.class);
        verify(historyRepository).save(historyCaptor.capture());
        UserTransactionHistory storedHistory = historyCaptor.getValue();

        ArgumentCaptor<ProcessedEvent> processedCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processedCaptor.capture());
        ProcessedEvent processedEvent = processedCaptor.getValue();

        ArgumentCaptor<FraudDetectedEvent> fraudEventCaptor = ArgumentCaptor.forClass(FraudDetectedEvent.class);
        verify(fraudEventPublisher).publish(fraudEventCaptor.capture());
        FraudDetectedEvent fraudEvent = fraudEventCaptor.getValue();

        assertNotNull(storedHistory.getOccurredAt());
        assertEquals(storedHistory.getOccurredAt(), processedEvent.getProcessedAt());
        assertFalse(processedEvent.getProcessedAt().isBefore(before));
        assertFalse(processedEvent.getProcessedAt().isAfter(after));

        assertEquals(event.transactionId(), fraudEvent.transactionId());
        assertEquals(event.userId(), fraudEvent.userId());
        assertEquals(80, fraudEvent.riskScore());
        assertEquals(List.of("HIGH_AMOUNT", "HIGH_RISK_MERCHANT"), fraudEvent.reasons());
        assertEquals("v1.0.0", fraudEvent.ruleVersion());
        assertTrue(fraudEvent.occurredAt().isAfter(before) || fraudEvent.occurredAt().equals(before));
    }

    private TransactionCreatedEvent buildEvent(String eventId, Instant occurredAt) {
        return new TransactionCreatedEvent(
                eventId,
                occurredAt,
                "tx-1",
                "user-1",
                new BigDecimal("250.00"),
                "USD",
                "MRC-101",
                "US",
                PaymentMethod.CARD
        );
    }
}
