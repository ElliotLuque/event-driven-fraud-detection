package com.fraud.e2e;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End-to-end test that verifies the full pipeline:
 * transaction-service -> Kafka -> fraud-detection-service -> Kafka -> alert-service
 *
 * Spins up all 3 microservices + Kafka + ZooKeeper + 3 PostgreSQL instances
 * using Docker Compose via Testcontainers.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullPipelineE2ETest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(4);

    @Container
    static final DockerComposeContainer<?> ENV = new DockerComposeContainer<>(
            new File("docker-compose-e2e.yml"))
            .withExposedService("transaction-service", 8080,
                    Wait.forHttp("/actuator/health").forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT))
            .withExposedService("alert-service", 8082,
                    Wait.forHttp("/actuator/health").forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT))
            .withExposedService("fraud-detection-service", 8081,
                    Wait.forHttp("/actuator/health").forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT));

    private static String transactionServiceUrl;
    private static String alertServiceUrl;

    @BeforeAll
    static void setUp() {
        String txHost = ENV.getServiceHost("transaction-service", 8080);
        int txPort = ENV.getServicePort("transaction-service", 8080);
        transactionServiceUrl = "http://" + txHost + ":" + txPort;

        String alertHost = ENV.getServiceHost("alert-service", 8082);
        int alertPort = ENV.getServicePort("alert-service", 8082);
        alertServiceUrl = "http://" + alertHost + ":" + alertPort;
    }

    /**
     * A high-amount transaction to a high-risk merchant should trigger a fraud alert
     * that appears in the alert-service after flowing through the full Kafka pipeline.
     */
    @Test
    @Order(1)
    void fraudulentTransaction_shouldCreateAlert() {
        String transactionId =
                given()
                        .contentType(ContentType.JSON)
                        .body(Map.of(
                                "userId", "user-e2e-fraud",
                                "amount", 15000.00,
                                "currency", "USD",
                                "merchantId", "MRC-999",
                                "country", "US",
                                "paymentMethod", "CARD"
                        ))
                .when()
                        .post(transactionServiceUrl + "/api/v1/transactions")
                .then()
                        .statusCode(201)
                        .body("transactionId", notNullValue())
                        .body("userId", equalTo("user-e2e-fraud"))
                        .body("amount", comparesEqualTo(15000.00f))
                .extract()
                        .path("transactionId");

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        given()
                        .when()
                                .get(alertServiceUrl + "/api/v1/alerts/users/user-e2e-fraud")
                        .then()
                                .statusCode(200)
                                .body("size()", greaterThanOrEqualTo(1))
                                .body("[0].transactionId", equalTo(transactionId))
                                .body("[0].userId", equalTo("user-e2e-fraud"))
                                .body("[0].riskScore", greaterThanOrEqualTo(45))
                                .body("[0].reasons", hasItem(containsString("HIGH_AMOUNT")))
                );
    }

    /**
     * A normal low-value transaction should NOT generate any fraud alert.
     */
    @Test
    @Order(2)
    void normalTransaction_shouldNotCreateAlert() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "userId", "user-e2e-normal",
                        "amount", 50.00,
                        "currency", "EUR",
                        "merchantId", "MRC-001",
                        "country", "ES",
                        "paymentMethod", "TRANSFER"
                ))
        .when()
                .post(transactionServiceUrl + "/api/v1/transactions")
        .then()
                .statusCode(201);

        // Wait long enough for the pipeline to process, then verify no alert was created
        await()
                .during(Duration.ofSeconds(10))
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        given()
                        .when()
                                .get(alertServiceUrl + "/api/v1/alerts/users/user-e2e-normal")
                        .then()
                                .statusCode(200)
                                .body("size()", equalTo(0))
                );
    }

    /**
     * The webhook endpoint should also trigger the full pipeline end-to-end.
     */
    @Test
    @Order(3)
    void webhookEndpoint_shouldAlsoTriggerFullPipeline() {
        String transactionId =
                given()
                        .contentType(ContentType.JSON)
                        .body(Map.of(
                                "userId", "user-e2e-webhook",
                                "amount", 20000.00,
                                "currency", "USD",
                                "merchantId", "MRC-666",
                                "country", "BR",
                                "paymentMethod", "WALLET"
                        ))
                .when()
                        .post(transactionServiceUrl + "/api/v1/webhooks/transactions")
                .then()
                        .statusCode(201)
                .extract()
                        .path("transactionId");

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        given()
                        .when()
                                .get(alertServiceUrl + "/api/v1/alerts/users/user-e2e-webhook")
                        .then()
                                .statusCode(200)
                                .body("size()", greaterThanOrEqualTo(1))
                                .body("[0].transactionId", equalTo(transactionId))
                                .body("[0].riskScore", greaterThanOrEqualTo(45))
                );
    }

    /**
     * A transaction triggering multiple fraud rules should accumulate the risk score.
     * HIGH_AMOUNT (+45) + HIGH_RISK_MERCHANT (+25) = riskScore >= 70
     */
    @Test
    @Order(4)
    void multipleRules_shouldAccumulateRiskScore() {
        String transactionId =
                given()
                        .contentType(ContentType.JSON)
                        .body(Map.of(
                                "userId", "user-e2e-multi",
                                "amount", 50000.00,
                                "currency", "USD",
                                "merchantId", "MRC-404",
                                "country", "US",
                                "paymentMethod", "CARD"
                        ))
                .when()
                        .post(transactionServiceUrl + "/api/v1/transactions")
                .then()
                        .statusCode(201)
                .extract()
                        .path("transactionId");

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        given()
                        .when()
                                .get(alertServiceUrl + "/api/v1/alerts/users/user-e2e-multi")
                        .then()
                                .statusCode(200)
                                .body("size()", greaterThanOrEqualTo(1))
                                .body("[0].transactionId", equalTo(transactionId))
                                .body("[0].riskScore", greaterThanOrEqualTo(70))
                                .body("[0].reasons", hasItem(containsString("HIGH_AMOUNT")))
                                .body("[0].reasons", hasItem(containsString("HIGH_RISK_MERCHANT")))
                );
    }

    /**
     * Invalid requests should be rejected with 400 at the API gateway level.
     */
    @Test
    @Order(5)
    void inputValidation_shouldRejectInvalidRequest() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "userId", "",
                        "amount", -1,
                        "currency", "invalid",
                        "merchantId", "",
                        "country", "INVALID",
                        "paymentMethod", "CARD"
                ))
        .when()
                .post(transactionServiceUrl + "/api/v1/transactions")
        .then()
                .statusCode(400);
    }
}
