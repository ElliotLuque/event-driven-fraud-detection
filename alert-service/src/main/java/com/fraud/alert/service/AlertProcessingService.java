package com.fraud.alert.service;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.mapping.AlertEventMapper;
import com.fraud.alert.model.Alert;
import com.fraud.alert.model.ProcessedEvent;
import com.fraud.alert.repository.AlertRepository;
import com.fraud.alert.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class AlertProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AlertProcessingService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final AlertRepository alertRepository;
    private final AlertEventMapper alertEventMapper;
    private final NotificationGateway notificationGateway;
    private final AlertMetrics alertMetrics;

    public AlertProcessingService(
            ProcessedEventRepository processedEventRepository,
            AlertRepository alertRepository,
            AlertEventMapper alertEventMapper,
            NotificationGateway notificationGateway,
            AlertMetrics alertMetrics
    ) {
        this.processedEventRepository = processedEventRepository;
        this.alertRepository = alertRepository;
        this.alertEventMapper = alertEventMapper;
        this.notificationGateway = notificationGateway;
        this.alertMetrics = alertMetrics;
    }

    @Transactional
    public void process(FraudDetectedEvent event) {
        String previousTraceId = MDC.get("traceId");
        String traceId = resolveTraceId(previousTraceId, event.traceId());
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

            if (processedEventRepository.existsById(event.eventId())) {
                log.info("alert_event_duplicate",
                        kv("event", "alert_event_duplicate"),
                        kv("outcome", "duplicate"),
                        kv("eventId", event.eventId()),
                        kv("transactionId", event.transactionId())
                );
                return;
            }

            long createdStartNanos = System.nanoTime();
            Instant now = Instant.now();
            Alert alert = alertEventMapper.toAlert(event, now, traceId);

            alertRepository.save(alert);
            processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
            long createDurationMs = (System.nanoTime() - createdStartNanos) / 1_000_000;
            alertMetrics.recordAlertCreated(alert);
            log.info("alert_created",
                    kv("event", "alert_created"),
                    kv("outcome", "success"),
                    kv("eventId", event.eventId()),
                    kv("transactionId", alert.getTransactionId()),
                    kv("alertId", alert.getId()),
                    kv("risk_score", alert.getRiskScore()),
                    kv("source_event_id", alert.getSourceEventId()),
                    kv("duration_ms", createDurationMs)
            );
            notificationGateway.notifyFraud(alert);
        } finally {
            if (injectedTraceId) {
                MDC.remove("traceId");
            }
        }
    }

    private static String resolveTraceId(String currentTraceId, String eventTraceId) {
        if (currentTraceId != null && !currentTraceId.isBlank()) {
            return currentTraceId;
        }
        return eventTraceId;
    }
}
