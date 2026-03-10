package com.fraud.alert.service;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.inbox.AlertInboxEvent;
import com.fraud.alert.inbox.AlertInboxRepository;
import com.fraud.alert.mapping.AlertEventMapper;
import com.fraud.alert.model.Alert;
import com.fraud.alert.model.ProcessedEvent;
import com.fraud.alert.repository.AlertRepository;
import com.fraud.alert.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class AlertProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AlertProcessingService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final AlertInboxRepository alertInboxRepository;
    private final AlertRepository alertRepository;
    private final AlertEventMapper alertEventMapper;
    private final NotificationGateway notificationGateway;
    private final AlertMetrics alertMetrics;

    public AlertProcessingService(
            ProcessedEventRepository processedEventRepository,
            AlertInboxRepository alertInboxRepository,
            AlertRepository alertRepository,
            AlertEventMapper alertEventMapper,
            NotificationGateway notificationGateway,
            AlertMetrics alertMetrics
    ) {
        this.processedEventRepository = processedEventRepository;
        this.alertInboxRepository = alertInboxRepository;
        this.alertRepository = alertRepository;
        this.alertEventMapper = alertEventMapper;
        this.notificationGateway = notificationGateway;
        this.alertMetrics = alertMetrics;
    }

    @Transactional
    public void process(FraudDetectedEvent event) {
        String traceId = resolveTraceId(event.traceId());
        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        boolean alreadyProcessed = ensureInboxEvent(event, occurredAt);

        if (alreadyProcessed) {
            log.info("alert_event_duplicate",
                    kv("event", "alert_event_duplicate"),
                    kv("outcome", "duplicate"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId())
            );
            return;
        }

        processRegisteredEvent(event, occurredAt, traceId);
    }

    @Transactional
    public void processIngestedEvent(FraudDetectedEvent event, Instant occurredAt) {
        String traceId = resolveTraceId(event.traceId());
        Instant effectiveOccurredAt = occurredAt != null ? occurredAt : Instant.now();
        processRegisteredEvent(event, effectiveOccurredAt, traceId);
    }

    private void processRegisteredEvent(FraudDetectedEvent event, Instant occurredAt, String traceId) {
        String previousTraceId = MDC.get("traceId");
        boolean injectedTraceId = false;
        if ((previousTraceId == null || previousTraceId.isBlank()) && traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
            injectedTraceId = true;
        }

        try {
            log.info("alert_event_consumed",
                    kv("event", "alert_event_consumed"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", event.transactionId()),
                    kv("risk_score", event.riskScore()),
                    kv("reasons_count", event.reasons() == null ? 0 : event.reasons().size())
            );

            Instant now = Instant.now();
            if (!tryMarkAsProcessed(event.eventId(), now)) {
                markInboxProcessed(event.eventId());
                log.info("alert_event_duplicate",
                        kv("event", "alert_event_duplicate"),
                        kv("outcome", "duplicate"),
                        kv("eventId", event.eventId()),
                        kv("transactionId", event.transactionId())
                );
                return;
            }

            long createdStartNanos = System.nanoTime();
            Alert alert = alertEventMapper.toAlert(event, now, traceId);

            alertRepository.save(alert);
            long createDurationMs = (System.nanoTime() - createdStartNanos) / 1_000_000;
            alertMetrics.recordAlertCreated(alert);
            Instant pipelineStart = event.transactionOccurredAt() != null ? event.transactionOccurredAt() : occurredAt;
            long pipelineE2eMs = pipelineStart == null
                    ? 0
                    : Math.max(0, Instant.now().toEpochMilli() - pipelineStart.toEpochMilli());
            alertMetrics.recordPipelineE2eFraud(pipelineE2eMs);
            log.info("alert_created",
                    kv("event", "alert_created"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", alert.getTransactionId()),
                    kv("alertId", alert.getId()),
                    kv("risk_score", alert.getRiskScore()),
                    kv("source_event_id", alert.getSourceEventId()),
                    kv("pipeline_e2e_duration_ms", pipelineE2eMs),
                    kv("duration_ms", createDurationMs)
            );
            notificationGateway.notifyFraud(alert);
            markInboxProcessed(event.eventId());
        } finally {
            if (injectedTraceId) {
                MDC.remove("traceId");
            }
        }
    }

    private String resolveTraceId(String eventTraceId) {
        if (eventTraceId != null && !eventTraceId.isBlank()) {
            return eventTraceId;
        }
        String currentTraceId = MDC.get("traceId");
        if (currentTraceId != null && !currentTraceId.isBlank()) {
            return currentTraceId;
        }
        return null;
    }

    private boolean ensureInboxEvent(FraudDetectedEvent event, Instant occurredAt) {
        int inserted = alertInboxRepository.insertReceivedIfAbsent(
                event.eventId(),
                event.transactionId(),
                event.traceId(),
                null,
                null,
                "INLINE_PROCESSING",
                occurredAt,
                Instant.now()
        );
        if (inserted > 0) {
            return false;
        }

        AlertInboxEvent inboxEvent = alertInboxRepository.lockByEventId(event.eventId())
                .orElseThrow(() -> new IllegalStateException("Alert inbox event not found after registration"));
        return inboxEvent.isProcessed();
    }

    private void markInboxProcessed(String eventId) {
        int updated = alertInboxRepository.markProcessed(eventId, Instant.now());
        if (updated == 0) {
            throw new IllegalStateException("Alert inbox event not found while marking as processed");
        }
    }

    private boolean tryMarkAsProcessed(String eventId, Instant processedAt) {
        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, processedAt));
            return true;
        } catch (DataIntegrityViolationException ignored) {
            return false;
        }
    }
}
