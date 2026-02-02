package com.fraud.transaction;

import com.fraud.transaction.api.TransactionRequest;
import com.fraud.transaction.api.TransactionResponse;
import com.fraud.transaction.domain.PaymentMethod;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.repository.TransactionRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.serializer.JsonDeserializer;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionFlowIntegrationTest {

    private static final String TRANSACTIONS_TOPIC = "transactions.created.it";

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
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TransactionRepository transactionRepository;

    @LocalServerPort
    private int port;

    @Test
    void createTransactionShouldPersistAndPublishEvent() {
        TransactionRequest request = new TransactionRequest(
                "user-integration",
                new BigDecimal("15000.00"),
                "USD",
                "MRC-999",
                "US",
                PaymentMethod.CARD
        );

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/transactions",
                request,
                TransactionResponse.class
        );

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(transactionRepository.existsById(response.getBody().transactionId()));

        try (Consumer<String, TransactionCreatedEvent> consumer = buildConsumer()) {
            consumer.subscribe(List.of(TRANSACTIONS_TOPIC));

            TransactionCreatedEvent publishedEvent = awaitEvent(
                    consumer,
                    response.getBody().transactionId(),
                    Duration.ofSeconds(20)
            );

            assertEquals(response.getBody().transactionId(), publishedEvent.transactionId());
            assertEquals("user-integration", publishedEvent.userId());
            assertEquals(new BigDecimal("15000.00"), publishedEvent.amount());
        }
    }

    private Consumer<String, TransactionCreatedEvent> buildConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "transaction-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionCreatedEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new KafkaConsumer<>(props);
    }

    private TransactionCreatedEvent awaitEvent(
            Consumer<String, TransactionCreatedEvent> consumer,
            String expectedTransactionId,
            Duration timeout
    ) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, TransactionCreatedEvent> record : consumer.poll(Duration.ofMillis(500))) {
                TransactionCreatedEvent value = record.value();
                if (value != null && expectedTransactionId.equals(value.transactionId())) {
                    return value;
                }
            }
        }
        throw new AssertionError("TransactionCreated event was not published within timeout");
    }
}
