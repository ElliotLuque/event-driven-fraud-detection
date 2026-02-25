package com.fraud.alert.service;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.mapping.AlertEventMapper;
import com.fraud.alert.model.Alert;
import com.fraud.alert.model.ProcessedEvent;
import com.fraud.alert.repository.AlertRepository;
import com.fraud.alert.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Event {} already processed, skipping", event.eventId());
            return;
        }

        Instant now = Instant.now();
        Alert alert = alertEventMapper.toAlert(event, now);

        alertRepository.save(alert);
        processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
        alertMetrics.recordAlertCreated(alert);
        notificationGateway.notifyFraud(alert);
    }
}
