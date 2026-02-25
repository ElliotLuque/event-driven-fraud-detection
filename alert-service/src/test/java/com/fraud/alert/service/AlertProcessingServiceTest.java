package com.fraud.alert.service;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.mapping.AlertEventMapper;
import com.fraud.alert.model.Alert;
import com.fraud.alert.model.ProcessedEvent;
import com.fraud.alert.repository.AlertRepository;
import com.fraud.alert.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertProcessingServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private AlertRepository alertRepository;

    @Spy
    private AlertEventMapper alertEventMapper = Mappers.getMapper(AlertEventMapper.class);

    @Mock
    private NotificationGateway notificationGateway;

    @Mock
    private AlertMetrics alertMetrics;

    @InjectMocks
    private AlertProcessingService alertProcessingService;

    @Test
    void processShouldSkipDuplicatedEvent() {
        FraudDetectedEvent event = buildEvent("evt-dup", List.of("HIGH_AMOUNT"));
        when(processedEventRepository.existsById("evt-dup")).thenReturn(true);

        alertProcessingService.process(event);

        verify(processedEventRepository).existsById("evt-dup");
        verify(alertRepository, never()).save(any(Alert.class));
        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
        verifyNoInteractions(alertMetrics, notificationGateway);
    }

    @Test
    void processShouldPersistAlertAndNotifyForNewEvent() {
        FraudDetectedEvent event = buildEvent("evt-1", List.of("HIGH_AMOUNT", "HIGH_RISK_MERCHANT"));
        when(processedEventRepository.existsById("evt-1")).thenReturn(false);

        alertProcessingService.process(event);

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(alertCaptor.capture());
        Alert storedAlert = alertCaptor.getValue();

        ArgumentCaptor<ProcessedEvent> processedCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processedCaptor.capture());
        ProcessedEvent storedProcessed = processedCaptor.getValue();

        verify(alertMetrics).recordAlertCreated(same(storedAlert));
        verify(notificationGateway).notifyFraud(same(storedAlert));

        assertNotNull(storedAlert.getId());
        assertEquals("tx-1", storedAlert.getTransactionId());
        assertEquals("user-1", storedAlert.getUserId());
        assertEquals(85, storedAlert.getRiskScore());
        assertEquals("HIGH_AMOUNT,HIGH_RISK_MERCHANT", storedAlert.getReasons());

        assertEquals("evt-1", storedProcessed.getEventId());
        assertEquals(storedAlert.getCreatedAt(), storedProcessed.getProcessedAt());
    }

    @Test
    void processShouldUseFallbackReasonWhenNoReasonsWereProvided() {
        FraudDetectedEvent event = buildEvent("evt-2", List.of());
        when(processedEventRepository.existsById("evt-2")).thenReturn(false);

        alertProcessingService.process(event);

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(alertCaptor.capture());

        assertEquals("UNSPECIFIED_RULE", alertCaptor.getValue().getReasons());
    }

    private FraudDetectedEvent buildEvent(String eventId, List<String> reasons) {
        return new FraudDetectedEvent(
                eventId,
                Instant.parse("2026-01-01T10:00:00Z"),
                "tx-1",
                "user-1",
                85,
                reasons,
                "v1.0.0"
        );
    }
}
