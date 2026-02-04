package com.fraud.detection;

import com.fraud.detection.events.FraudDetectedEvent;
import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.model.PaymentMethod;
import com.fraud.detection.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FraudDetectionFlowIntegrationTest {

    private static final String TRANSACTIONS_TOPIC = "transactions.created.it";
    private static final String FRAUD_TOPIC = "fraud.detected.it";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("app.kafka.topics.transactions-created", () -> TRANSACTIONS_TOPIC);
        registry.add("app.kafka.topics.fraud-detected", () -> FRAUD_TOPIC);
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void shouldConsumeTransactionAndPublishFraudEvent() {
        String sourceEventId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();

        TransactionCreatedEvent input = new TransactionCreatedEvent(
                sourceEventId,
                Instant.now(),
                transactionId,
                "user-fraud-it",
                new BigDecimal("15000.00"),
                "USD",
                "MRC-100",
                "US",
                PaymentMethod.CARD
        );

        try (KafkaProducer<String, TransactionCreatedEvent> producer = buildInputProducer();
             Consumer<String, FraudDetectedEvent> consumer = buildFraudConsumer()) {

            producer.send(new ProducerRecord<>(TRANSACTIONS_TOPIC, input.transactionId(), input));
            producer.flush();

            consumer.subscribe(List.of(FRAUD_TOPIC));
            FraudDetectedEvent output = awaitEvent(consumer, transactionId, Duration.ofSeconds(25));

            assertEquals(transactionId, output.transactionId());
            assertTrue(output.riskScore() >= 45);
            assertTrue(output.reasons().contains("HIGH_AMOUNT"));
            assertTrue(processedEventRepository.existsById(sourceEventId));
        }
    }

    private KafkaProducer<String, TransactionCreatedEvent> buildInputProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class.getName());
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new KafkaProducer<>(props);
    }

    private Consumer<String, FraudDetectedEvent> buildFraudConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "fraud-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, FraudDetectedEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new KafkaConsumer<>(props);
    }

    private FraudDetectedEvent awaitEvent(
            Consumer<String, FraudDetectedEvent> consumer,
            String expectedTransactionId,
            Duration timeout
    ) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, FraudDetectedEvent> record : consumer.poll(Duration.ofMillis(500))) {
                FraudDetectedEvent value = record.value();
                if (value != null && expectedTransactionId.equals(value.transactionId())) {
                    return value;
                }
            }
        }
        throw new AssertionError("FraudDetected event was not published within timeout");
    }
}
