package com.fraud.alert.messaging;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.service.AlertProcessingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FraudDetectedConsumer {

    private final AlertProcessingService alertProcessingService;

    public FraudDetectedConsumer(AlertProcessingService alertProcessingService) {
        this.alertProcessingService = alertProcessingService;
    }

    @KafkaListener(topics = "${app.kafka.topics.fraud-detected}")
    public void consume(FraudDetectedEvent event) {
        alertProcessingService.process(event);
    }
}
