package com.fraud.e2e;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    static final ComposeContainer ENV = new ComposeContainer(
            new File("docker-compose-e2e.yml"))
            .withLocalCompose(true)
            .withExposedService("transaction-service-1", 8080,
                    Wait.forHttp("/actuator/health").forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT))
            .withExposedService("alert-service-1", 8082,
                    Wait.forHttp("/actuator/health").forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT))
            .withExposedService("fraud-detection-service-1", 8081,
                    Wait.forHttp("/actuator/health").forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT));

    private static String transactionServiceUrl;
    private static String alertServiceUrl;

    private record LoadResult(int statusCode, long latencyMs, boolean fraudulent, String userId) {
    }

    @BeforeAll
    static void setUp() {
        String txHost = ENV.getServiceHost("transaction-service-1", 8080);
        int txPort = ENV.getServicePort("transaction-service-1", 8080);
        transactionServiceUrl = "http://" + txHost + ":" + txPort;

        String alertHost = ENV.getServiceHost("alert-service-1", 8082);
        int alertPort = ENV.getServicePort("alert-service-1", 8082);
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

    @Test
    @Order(6)
    void mixedLoad_shouldKeepApiHealthyAndGenerateExpectedAlerts() throws Exception {
        int totalRequests = 120;
        int fraudulentRequests = 48;
        int concurrency = 12;
        String runId = "run" + System.currentTimeMillis();

        int initialAlertCount =
                given()
                .when()
                        .get(alertServiceUrl + "/api/v1/alerts")
                .then()
                        .statusCode(200)
                .extract()
                        .path("size()");

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<CompletableFuture<LoadResult>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            boolean fraudulent = i < fraudulentRequests;
            String userId = "user-e2e-load-" + runId + "-" + i;
            String path = i % 4 == 0 ? "/api/v1/webhooks/transactions" : "/api/v1/transactions";

            Map<String, Object> payload = fraudulent
                    ? Map.of(
                    "userId", userId,
                    "amount", 25000.00,
                    "currency", "USD",
                    "merchantId", "MRC-999",
                    "country", "US",
                    "paymentMethod", "CARD"
            )
                    : Map.of(
                    "userId", userId,
                    "amount", 15.00,
                    "currency", "USD",
                    "merchantId", "MRC-001",
                    "country", "US",
                    "paymentMethod", "TRANSFER"
            );

            futures.add(CompletableFuture.supplyAsync(() -> {
                long start = System.nanoTime();
                int statusCode =
                        given()
                                .contentType(ContentType.JSON)
                                .body(payload)
                        .when()
                                .post(transactionServiceUrl + path)
                        .then()
                                .extract()
                                .statusCode();
                long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                return new LoadResult(statusCode, latencyMs, fraudulent, userId);
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(90, TimeUnit.SECONDS);
        executor.shutdown();

        List<LoadResult> results = futures.stream().map(CompletableFuture::join).toList();
        long failedRequests = results.stream().filter(result -> result.statusCode() != 201).count();
        double errorRate = failedRequests / (double) totalRequests;
        assertTrue(errorRate <= 0.02, "API error rate should stay below 2% under load");

        List<Long> latencies = results.stream()
                .map(LoadResult::latencyMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
        long p95LatencyMs = latencies.get(Math.max(0, p95Index));
        assertTrue(p95LatencyMs < 2500, "P95 latency should stay below 2.5s under load");

        await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    int currentAlertCount =
                            given()
                            .when()
                                    .get(alertServiceUrl + "/api/v1/alerts")
                            .then()
                                    .statusCode(200)
                            .extract()
                                    .path("size()");
                    assertTrue(currentAlertCount >= initialAlertCount + fraudulentRequests,
                            "Fraud alerts should be generated for load scenario");
                });

        List<String> sampleFraudUsers = results.stream()
                .filter(LoadResult::fraudulent)
                .map(LoadResult::userId)
                .limit(5)
                .toList();

        for (String userId : sampleFraudUsers) {
            given()
            .when()
                    .get(alertServiceUrl + "/api/v1/alerts/users/" + userId)
            .then()
                    .statusCode(200)
                    .body("size()", equalTo(1))
                    .body("[0].userId", equalTo(userId))
                    .body("[0].riskScore", greaterThanOrEqualTo(45));
        }

        List<String> sampleNormalUsers = results.stream()
                .filter(result -> !result.fraudulent())
                .map(LoadResult::userId)
                .limit(5)
                .toList();

        for (String userId : sampleNormalUsers) {
            int alerts =
                    given()
                    .when()
                            .get(alertServiceUrl + "/api/v1/alerts/users/" + userId)
                    .then()
                            .statusCode(200)
                    .extract()
                            .path("size()");
            assertEquals(0, alerts, "Normal traffic should not create alerts");
        }
    }
}
