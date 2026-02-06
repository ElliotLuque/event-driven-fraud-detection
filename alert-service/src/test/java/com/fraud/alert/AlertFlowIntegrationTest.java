package com.fraud.alert;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.model.Alert;
import com.fraud.alert.repository.AlertRepository;
import com.fraud.alert.repository.ProcessedEventRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class AlertFlowIntegrationTest {

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
        registry.add("app.kafka.topics.fraud-detected", () -> FRAUD_TOPIC);
    }

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void shouldConsumeFraudEventAndPersistAlert() {
        String eventId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();

        FraudDetectedEvent event = new FraudDetectedEvent(
                eventId,
                Instant.now(),
                transactionId,
                "user-alert-it",
                80,
                List.of("HIGH_AMOUNT", "HIGH_RISK_MERCHANT"),
                "v1.0.0"
        );

        try (KafkaProducer<String, FraudDetectedEvent> producer = buildProducer()) {
            producer.send(new ProducerRecord<>(FRAUD_TOPIC, transactionId, event));
            producer.flush();
        }

        Alert alert = awaitAlert(transactionId, Duration.ofSeconds(25));

        assertEquals("user-alert-it", alert.getUserId());
        assertEquals(80, alert.getRiskScore());
        assertTrue(alert.getReasons().contains("HIGH_AMOUNT"));
        assertTrue(processedEventRepository.existsById(eventId));
    }

    private KafkaProducer<String, FraudDetectedEvent> buildProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class.getName());
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new KafkaProducer<>(props);
    }

    private Alert awaitAlert(String transactionId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<Alert> alert = alertRepository.findByTransactionId(transactionId);
            if (alert.isPresent()) {
                return alert.get();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for alert", ex);
            }
        }
        throw new AssertionError("Alert was not stored within timeout");
    }
}
