# Event-Driven Fraud Detection

Backend orientado a eventos para registrar transacciones financieras, detectar fraude de forma asíncrona y generar alertas sin bloquear la operación principal.

## Arquitectura

```text
Client/API/Webhook
      |
      v
Transaction Service --> PostgreSQL (transactions)
      |
      v
Kafka topic: transactions.created
      |
      v
Fraud Detection Service --> PostgreSQL (fraud history + idempotency)
      |
      v
Kafka topic: fraud.detected
      |
      v
Alert Service --> PostgreSQL (alerts + idempotency)
```

### Servicios

1. `transaction-service`
    - Expone API REST y endpoint webhook.
    - Persiste la transaccion.
    - Publica evento `TransactionCreated` en Kafka.

2. `fraud-detection-service`
    - Consume `TransactionCreated`.
    - Aplica reglas de fraude.
    - Publica `FraudDetected` cuando corresponde.

3. `alert-service`
    - Consume `FraudDetected`.
    - Registra alertas.
    - Simula notificacion via logs.

## Resiliencia incluida

- Idempotencia en consumidores (`eventId` en tabla `processed_events`).
- Retries de consumidor con backoff fijo.
- Dead Letter Topic (DLQ) por topico principal.
- Persistencia local por servicio para mantener responsabilidades separadas.

## Reglas de fraude incluidas (MVP)

- `HIGH_AMOUNT`: monto superior al umbral.
- `HIGH_VELOCITY`: muchas transacciones en ventana corta.
- `COUNTRY_CHANGE_IN_SHORT_WINDOW`: cambio de pais sospechoso.
- `HIGH_RISK_MERCHANT`: comercio en lista de riesgo.

El score final se calcula por suma de reglas y se limita a 100.

## Topics Kafka

- `transactions.created`
- `fraud.detected`
- `transactions.created.dlq`
- `fraud.detected.dlq`

Cada consumidor incluye:

- Idempotencia basada en `eventId`.
- Retries con backoff.
- Envio automatico a DLQ cuando excede reintentos.

## Stack

- Java 21
- Spring Boot 3
- Spring Data JPA
- Spring Kafka
- PostgreSQL
- Docker Compose

## Ejecutar localmente con Docker

```bash
docker compose up -d --build
```

Servicios disponibles:

- Transaction Service: `http://localhost:8080`
- Fraud Detection Service: `http://localhost:8081`
- Alert Service: `http://localhost:8082`
- Kafka UI: `http://localhost:8089`

Variables utiles para pruebas locales:

- `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

Para ver logs:

```bash
docker compose logs -f transaction-service fraud-detection-service alert-service
```

## API principal

### Crear transaccion (REST)

`POST http://localhost:8080/api/v1/transactions`

```json
{
  "userId": "user-123",
  "amount": 15000,
  "currency": "USD",
  "merchantId": "MRC-999",
  "country": "US",
  "paymentMethod": "CARD"
}
```

### Crear transaccion (webhook)

`POST http://localhost:8080/api/v1/webhooks/transactions`

Mismo payload que el endpoint REST.

### Consultar alertas

- `GET http://localhost:8082/api/v1/alerts`
- `GET http://localhost:8082/api/v1/alerts/users/{userId}`

## Smoke test end-to-end

```bash
bash scripts/smoke-test.sh
```

El script crea una transaccion de alto riesgo y consulta las alertas del usuario generado.

## Desarrollo local sin Docker (opcional)

Levanta Kafka + PostgreSQL con Docker y luego ejecuta cada servicio:

```bash
mvn -pl transaction-service spring-boot:run
mvn -pl fraud-detection-service spring-boot:run
mvn -pl alert-service spring-boot:run
```

## Tests

```bash
mvn test
```

Incluye:

- Test unitario del motor de reglas (`fraud-detection-service`).
- Test de integracion con Kafka + PostgreSQL en `transaction-service`.
- Test de integracion con Kafka + PostgreSQL en `fraud-detection-service`.
- Test de integracion con Kafka + PostgreSQL en `alert-service`.

Los tests de integracion usan Testcontainers y se omiten automaticamente si Docker no esta disponible.

Si no tienes Maven instalado localmente, puedes ejecutar tests con Docker:

```bash
docker run --rm -v "$PWD/transaction-service:/app" -w /app maven:3.9.9-eclipse-temurin-21 mvn -B test
docker run --rm -v "$PWD/fraud-detection-service:/app" -w /app maven:3.9.9-eclipse-temurin-21 mvn -B test
docker run --rm -v "$PWD/alert-service:/app" -w /app maven:3.9.9-eclipse-temurin-21 mvn -B test
```
