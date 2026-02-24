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
- Prometheus
- Loki
- Grafana

## IntelliJ IDEA

Este repositorio ya esta preparado para IntelliJ con:

- Proyecto Maven multi-modulo (importa el `pom.xml` raiz).
- Configuraciones compartidas en `.run/` para levantar cada microservicio desde el IDE.

Pasos recomendados:

1. Abre IntelliJ y selecciona `Open` sobre la carpeta del proyecto.
2. Cuando IntelliJ detecte Maven, importa el proyecto desde el `pom.xml` raiz.
3. Configura JDK 21 en `File > Project Structure`.
4. Abre `Run/Debug Configurations` y ejecuta una de estas configuraciones:
   - `transaction-service`
   - `fraud-detection-service`
   - `alert-service`

Nota: para ejecutar los tres servicios en paralelo desde IntelliJ, inicia las tres configuraciones una por una (o crea un Compound Run Configuration en el IDE).

## Ejecutar localmente con Docker

```bash
docker compose up -d --build
```

Servicios disponibles:

- Transaction Service: `http://localhost:8080`
- Fraud Detection Service: `http://localhost:8081`
- Alert Service: `http://localhost:8082`
- Kafka UI: `http://localhost:8089`
- Prometheus: `http://localhost:9090`
- Loki API: `http://localhost:3100`
- Grafana: `http://localhost:3000` (usuario `admin`, password `admin`)

Variables utiles para pruebas locales:

- `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

Para ver logs:

```bash
docker compose logs -f transaction-service fraud-detection-service alert-service
```

## Observabilidad

El proyecto incluye un stack de observabilidad listo para usar con Docker Compose:

- `Prometheus` para metricas de los microservicios (`/actuator/prometheus`).
- `Loki` para almacenamiento de logs.
- `Promtail` para recoleccion de logs de contenedores Docker.
- `Grafana` con datasources de Prometheus y Loki preconfigurados.
- Dashboard provisionado automaticamente: `Fraud Detection Observability`.
- Dashboard de alertas en vivo: `Fraud Alerting Live`.

Endpoints de metricas expuestos por servicio:

- `http://localhost:8080/actuator/prometheus`
- `http://localhost:8081/actuator/prometheus`
- `http://localhost:8082/actuator/prometheus`

Metricas de fraude disponibles:

- `fraud_alerts_total`: total de alertas de fraude creadas.
- `fraud_alert_risk_score_*`: distribucion de score de riesgo.

Regla de alerta Prometheus incluida:

- `FraudAlertDetected`: se activa cuando `increase(fraud_alerts_total[1m]) > 0`.

Consultas rapidas:

- Tasa de requests por servicio en Prometheus:

```promql
sum(rate(http_server_requests_seconds_count[1m])) by (application)
```

- Uso de heap JVM por servicio:

```promql
sum(jvm_memory_used_bytes{area="heap"}) by (application)
```

### Verlo en accion en Grafana

1. Levanta todo el stack:

```bash
docker compose up -d --build
```

2. Genera trafico y eventos de fraude:

```bash
bash scripts/generate-traffic.sh
```

Tambien puedes ejecutar el smoke test puntual:

```bash
bash scripts/smoke-test.sh
```

3. Abre Grafana en `http://localhost:3000` e inicia sesion con `admin` / `admin`.
4. Ve a `Dashboards` y abre `Fraud Detection Observability`.
5. En el dashboard revisa:

- `HTTP Throughput` para ver el trafico por servicio.
- `P95 HTTP Latency` para latencia.
- `HTTP 5xx (5m)` para errores.
- `Container Logs` para logs en vivo (filtrables por servicio).

6. Abre tambien el dashboard `Fraud Alerting Live` para ver:

- `Fraud Alerts (1m)` con el total del ultimo minuto.
- `Prometheus Rule Status` (`OK` / `FIRING`) para la regla `FraudAlertDetected`.
- `Fraud Alert Rate` y `Average Fraud Risk Score`.
- `Fraud Alert Logs` filtrado a eventos `Sending FRAUD alert`.

Tip: para ver mas actividad en logs, deja abierto el panel `Container Logs` y vuelve a correr `bash scripts/generate-traffic.sh`.

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

## Troubleshooting rapido

- Si Kafka tarda en responder, valida que `kafka` este en estado healthy en Docker Compose.
- Si los tests de integracion fallan localmente, confirma que Docker tenga memoria suficiente.
- Si no ves alertas, espera unos segundos y revisa logs de `fraud-detection-service` y `alert-service`.
