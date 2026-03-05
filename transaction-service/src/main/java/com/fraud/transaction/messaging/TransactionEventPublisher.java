package com.fraud.transaction.messaging;

import com.fraud.transaction.events.TransactionCreatedEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class TransactionEventPublisher {

    private final KafkaTemplate<String, TransactionCreatedEvent> kafkaTemplate;
    private final String topic;

    public TransactionEventPublisher(
            KafkaTemplate<String, TransactionCreatedEvent> kafkaTemplate,
            @Value("${app.kafka.topics.transactions-created}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(TransactionCreatedEvent event) {
        publish(topic, event.transactionId(), event);
    }

    public void publish(String topic, String key, TransactionCreatedEvent event) {
        try {
            publishAsync(topic, key, event).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publish interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Unable to publish transaction event", ex);
        }
    }

    public CompletableFuture<Void> publishAsync(String topic, String key, TransactionCreatedEvent event) {
        ProducerRecord<String, TransactionCreatedEvent> record = new ProducerRecord<>(topic, key, event);
        return kafkaTemplate.send(record)
                .orTimeout(5, TimeUnit.SECONDS)
                .thenApply(ignored -> null);
    }
}
