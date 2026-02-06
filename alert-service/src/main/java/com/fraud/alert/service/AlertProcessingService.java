package com.fraud.alert.service;

import com.fraud.alert.events.FraudDetectedEvent;
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
    private final NotificationGateway notificationGateway;

    public AlertProcessingService(
            ProcessedEventRepository processedEventRepository,
            AlertRepository alertRepository,
            NotificationGateway notificationGateway
    ) {
        this.processedEventRepository = processedEventRepository;
        this.alertRepository = alertRepository;
        this.notificationGateway = notificationGateway;
    }

    @Transactional
    public void process(FraudDetectedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Event {} already processed, skipping", event.eventId());
            return;
        }

        Instant now = Instant.now();
        Alert alert = Alert.fromEvent(event, now);

        alertRepository.save(alert);
        processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
        notificationGateway.notifyFraud(alert);
    }
}
