package com.fraud.detection.messaging;

import com.fraud.detection.events.FraudDetectedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class FraudEventPublisher {

    private final KafkaTemplate<String, FraudDetectedEvent> kafkaTemplate;
    private final String topic;

    public FraudEventPublisher(
            KafkaTemplate<String, FraudDetectedEvent> kafkaTemplate,
            @Value("${app.kafka.topics.fraud-detected}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(FraudDetectedEvent event) {
        try {
            kafkaTemplate.send(topic, event.transactionId(), event).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publish interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Unable to publish fraud event", ex);
        }
    }
}
