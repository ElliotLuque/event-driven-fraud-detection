package com.fraud.detection.config;

import com.fraud.detection.events.TransactionCreatedEvent;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Configuration
public class KafkaErrorHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlerConfig.class);

    @Bean
    public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".dlq", record.partition())
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        errorHandler.setLogLevel(KafkaException.Level.DEBUG);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                {
                    if (deliveryAttempt <= 1) {
                        log.warn("transaction_event_retry",
                                kv("event", "transaction_event_retry"),
                                kv("outcome", "retry"),
                                kv("topic", record.topic()),
                                kv("partition", record.partition()),
                                kv("offset", record.offset()),
                                kv("traceId", extractTraceId(record.value())),
                                kv("eventId", extractEventId(record.value())),
                                kv("transactionId", extractTransactionId(record.value())),
                                kv("delivery_attempt", deliveryAttempt),
                                kv("error_class", ex.getClass().getSimpleName()),
                                kv("error_message", ex.getMessage())
                        );
                    }
                }
        );
        return errorHandler;
    }

    private static String extractTraceId(Object value) {
        if (value instanceof TransactionCreatedEvent event) {
            return event.traceId();
        }
        return null;
    }

    private static String extractEventId(Object value) {
        if (value instanceof TransactionCreatedEvent event) {
            return event.eventId();
        }
        return null;
    }

    private static String extractTransactionId(Object value) {
        if (value instanceof TransactionCreatedEvent event) {
            return event.transactionId();
        }
        return null;
    }
}
