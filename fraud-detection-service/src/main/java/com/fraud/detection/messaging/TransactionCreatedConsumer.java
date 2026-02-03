package com.fraud.detection.messaging;

import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.service.FraudDetectionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionCreatedConsumer {

    private final FraudDetectionService fraudDetectionService;

    public TransactionCreatedConsumer(FraudDetectionService fraudDetectionService) {
        this.fraudDetectionService = fraudDetectionService;
    }

    @KafkaListener(topics = "${app.kafka.topics.transactions-created}")
    public void consume(TransactionCreatedEvent event) {
        fraudDetectionService.process(event);
    }
}
