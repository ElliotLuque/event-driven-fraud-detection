---
title: "Event-Driven Fraud Detection"
description: "Arquitectura antifraude event-driven con Transactional Outbox, Kafka particionado, observabilidad operativa y validación bajo carga reproducible."
image: "/images/event-driven-fraud-detection.png"
repository: "https://github.com/ElliotLuque/event-driven-fraud-detection"
technologies:
  [
    "Java",
    "Spring Boot",
    "Kafka",
    "PostgreSQL",
    "Grafana",
    "Prometheus",
    "Loki",
    "Tempo",
  ]
date: 2026-02-27
id: event-driven-fraud-detection-architecture
slug: "event-driven-fraud-detection-architecture"
---

## TL;DR

En este proyecto me propuse construir una plataforma antifraude event-driven que no solo funcione, sino que también sea operable cuando las cosas se complican.

El `transaction-service` usa **Transactional Outbox** con relay asíncrono, retries, limpieza periódica de eventos `PUBLISHED` en outbox (con retención configurable) y propagación de contexto de tracing para desacoplar persistencia de negocio y publicación de eventos.

El runtime local escala con `x6` réplicas por microservicio detrás de gateways NGINX, Kafka opera como clúster de 3 brokers con topics particionados (18 por defecto), y la observabilidad cubre capacidad/backpressure, infraestructura y SLOs de negocio.

No es solo una demo funcional: es un caso de estudio con resiliencia explícita, trazabilidad end-to-end y una estrategia de validación reproducible (unit + integration + e2e + carga con `k6`).

---

## Contexto y motivación

Cuando fraude y escritura transaccional viven en el mismo flujo síncrono, compiten por latencia y disponibilidad.

El objetivo práctico fue desacoplar el análisis antifraude sin perder control operacional, con piezas concretas para operar mejor bajo fallos reales:

- consistencia entre DB y publicación de eventos (Outbox),
- semántica `at-least-once` con consumidores idempotentes,
- DLQ como mecanismo operativo (no solo "parking lot"),
- observabilidad orientada a negocio y no solo a infraestructura.

[Imagen sugerida: comparativa sencilla del flujo síncrono acoplado vs flujo event-driven desacoplado con Outbox]

---

## Objetivo técnico

El foco técnico es que el pipeline funcione bajo presión sin perder control:

1. Mantener alta velocidad de escritura de transacciones.
2. Cerrar la brecha de consistencia DB/Kafka en `transaction-service`.
3. Escalar procesamiento con particionado real y réplicas.
4. Mejorar triage con trazas, logs estructurados y métricas por etapa.
5. Validar comportamiento de forma reproducible con carga mixta y escenarios de error.

---

## Arquitectura implementada

La arquitectura se organiza en tres dominios funcionales con capa de entrada balanceada y salida transaccional robusta:

- `transaction-gateway` (NGINX :8080) -> `transaction-service x6`.
- `fraud-gateway` (NGINX :8081) -> `fraud-detection-service x6`.
- `alert-gateway` (NGINX :8082) -> `alert-service x6`.

Servicios por responsabilidad:

- `transaction-service`
  - expone API REST + webhook,
  - persiste transacción,
  - encola `TransactionCreatedEvent` en `transaction_outbox` dentro de la misma transacción de BD,
  - un relay asíncrono publica a Kafka y marca publicados/fallidos.

- `fraud-detection-service`
  - consume `transactions.created`,
  - evalúa reglas heurísticas,
  - publica `fraud.detected` cuando `riskScore` supera umbral,
  - mantiene historial + deduplicación por `eventId`.

- `alert-service`
  - consume `fraud.detected`,
  - persiste alertas,
  - notifica por canales (log siempre activo + email opcional),
  - expone consultas de alertas para verificación y triage.

Cada servicio conserva su base PostgreSQL dedicada, y `transaction-service` agrega la tabla `transaction_outbox` para desacoplar persistencia de negocio y publicación asíncrona.

[Imagen sugerida: diagrama por capas (gateways -> servicios x6 -> Kafka -> PostgreSQL por servicio)]

![Diagrama de arquitectura completo con servicios, topics y bases de datos](/images/event-driven-fraud-detection-01.png)

---

## Topología Kafka y escalabilidad

El stack local levanta un clúster Kafka de 3 brokers (`kafka`, `kafka-2`, `kafka-3`) con configuración orientada a tolerancia de fallo local:

- `min.insync.replicas=2`
- replication factor por defecto `3`
- particiones por topic configurables (`APP_KAFKA_PARTITIONS`, default `18`)

Topics principales:

- `transactions.created`
- `fraud.detected`
- `transactions.created.dlq`
- `fraud.detected.dlq`

Esta combinación permitió probar paralelismo real por partición y observar mejor el comportamiento de lag/backpressure en escenarios de carga sostenida.

[Imagen sugerida: captura de métricas de particiones y consumer lag por grupo en carga sostenida]

---

## Flujo operativo end-to-end

1. Cliente envía transacción por REST o webhook al gateway.
2. `transaction-service` valida payload y persiste en PostgreSQL.
3. En la misma transacción, encola evento en `transaction_outbox` (JSONB).
4. `TransactionOutboxRelayService` toma lote pendiente con `FOR UPDATE SKIP LOCKED`, publica a Kafka y marca estado (`PUBLISHED` o retry con `nextAttemptAt`).
5. `fraud-detection-service` consume, deduplica por `eventId`, evalúa reglas y decide `clean|fraud`.
6. Si es fraude, publica `FraudDetectedEvent` con `ruleVersion`.
7. `alert-service` consume, deduplica, persiste alerta y ejecuta notificaciones por canal.
8. Si falla consumo, aplica retries y luego DLQ; consumidores DLQ intentan reproceso y registran métricas de éxito/fallo.

La separación entre escritura síncrona y publicación asíncrona es clave para mantener latencia estable y consistencia en el borde de entrada.

[Imagen sugerida: diagrama de secuencia end-to-end desde API/webhook hasta alerta o DLQ]

---

## Resiliencia y consistencia

### Transactional Outbox

El `transaction-service` evita dual-write directo DB/Kafka mediante Outbox:

- la transacción de negocio y el encolado de evento comparten commit,
- el relay publica fuera del request path,
- cada intento queda trazado por estado, intentos y último error.

Puntos técnicos relevantes:

- loteo + lock pesimista no bloqueante (`SKIP LOCKED`),
- retries con delay configurable,
- cleanup periódico de eventos ya publicados,
- propagación de `traceparent` y `baggage` para conservar continuidad de trazas.

### Idempotencia concurrente en consumidores

El enfoque idempotente en `fraud-detection-service` y `alert-service` se basa en:

- tabla `processed_events`,
- inserción atómica con `saveAndFlush`,
- manejo de `DataIntegrityViolationException` para neutralizar duplicados en carrera.

### Retries, DLQ y reproceso operativo

La estrategia de consumo usa backoff fijo (1s, 3 intentos). Si no hay recuperación, el mensaje se deriva a `<topic>.dlq`.

La DLQ no queda pasiva:

- existen consumidores dedicados por DLQ,
- se registran métricas `received/reprocessed/failed`,
- hay scripts para forzar y verificar fallo/reproceso (`scripts/test-dlq.sh`, `scripts/test-dlq-reprocess.sh`).

---

## Fragmento de código representativo

Este bloque resume el corazón del relay Outbox: lock de lote pendiente, publicación asíncrona y transición de estado por evento.

```java title=transaction-service/src/main/java/com/fraud/transaction/outbox/TransactionOutboxRelayService.java
@Scheduled(
        fixedDelayString = "${app.outbox.relay-interval-ms:200}",
        initialDelayString = "${app.outbox.relay-initial-delay-ms:1000}"
)
@Transactional
public void relayPendingEvents() {
    Instant now = Instant.now();
    List<TransactionOutboxEvent> pendingBatch = transactionOutboxRepository.lockPendingBatch(now, batchSize);
    if (pendingBatch.isEmpty()) {
        return;
    }

    int publishedCount = 0;
    int failedCount = 0;
    List<PendingPublication> pendingPublications = new ArrayList<>(pendingBatch.size());

    for (TransactionOutboxEvent outboxEvent : pendingBatch) {
        long publishStartedNanos = System.nanoTime();
        try {
            TransactionCreatedEvent event = objectMapper.treeToValue(outboxEvent.getPayload(), TransactionCreatedEvent.class);
            CompletableFuture<Void> publishFuture = transactionEventPublisher.publishAsync(
                    outboxEvent.getTopic(),
                    outboxEvent.getEventKey(),
                    event
            );
            pendingPublications.add(new PendingPublication(outboxEvent, publishFuture, publishStartedNanos));
        } catch (Exception ex) {
            long publishDurationMs = (System.nanoTime() - publishStartedNanos) / 1_000_000;
            transactionMetrics.recordTransactionEventPublished("failed", publishDurationMs);
            outboxEvent.markFailed(resolveErrorMessage(ex), Instant.now().plus(retryDelay));
            failedCount++;
        }
    }

    for (PendingPublication publication : pendingPublications) {
        long publishDurationMs = (System.nanoTime() - publication.publishStartedNanos()) / 1_000_000;
        try {
            publication.publishFuture().join();
            transactionMetrics.recordTransactionEventPublished("success", publishDurationMs);
            publication.outboxEvent().markPublished(Instant.now());
            publishedCount++;
        } catch (CompletionException ex) {
            transactionMetrics.recordTransactionEventPublished("failed", publishDurationMs);
            publication.outboxEvent().markFailed(resolveErrorMessage(ex), Instant.now().plus(retryDelay));
            failedCount++;
        }
    }

    log.info("transaction_outbox_batch_processed",
            kv("event", "transaction_outbox_batch_processed"),
            kv("batch_size", pendingBatch.size()),
            kv("published", publishedCount),
            kv("failed", failedCount)
    );
}
```

Este patrón desacopla latencia de API y latencia de broker sin perder rastreabilidad operativa por evento.

---

## Motor de reglas y contratos de eventos

### Reglas de fraude activas

Se mantiene un motor heurístico acumulativo con score cap en 100 y umbral configurable (`fraud-score-threshold`, default 70).

Reglas activas:

- `HIGH_AMOUNT` (+45)
- `HIGH_VELOCITY` (+35)
- `COUNTRY_CHANGE_IN_SHORT_WINDOW` (+30)
- `HIGH_RISK_MERCHANT` (+25)

El evento `FraudDetectedEvent` incluye `ruleVersion`, lo que facilita trazabilidad de decisiones cuando el motor de reglas de detección evolucione.

---

## Observabilidad operativa

El stack de observabilidad cubre métricas de aplicación, infraestructura y trazabilidad distribuida:

- Prometheus (servicios + exporters)
- Loki (logs estructurados)
- Alloy (colección de logs Docker + recepción OTLP)
- Tempo (trazas distribuidas)
- Grafana (dashboards y exploración)
- Kafka Exporter + Postgres Exporters + cAdvisor (capacidad e infraestructura)

Dashboards activos:

- `fraud-observability`
- `fraud-alerting-live`
- `fraud-tracing`
- `fraud-alert-triage-db`
- `fraud-kafka-operations`
- `fraud-throughput-live`
- `fraud-capacity-backpressure`

### Métricas que más valor dieron

- `transaction_events_enqueued_total{outcome}`
- `transaction_events_published_total{outcome}`
- `fraud_decisions_total{decision}`
- `fraud_alerts_total`
- `fraud_alert_notifications_total{channel,outcome}`
- `kafka_dlq_events_received_total`
- `kafka_dlq_events_reprocessed_total`
- `kafka_dlq_events_failed_total`

### Alertas de negocio y confiabilidad

Se incorporaron reglas SLO de cobertura y conversión del pipeline:

- `FraudPipelineCoverageSLOViolation`
- `FraudToAlertConversionSLOViolation`
- `NotificationFailureRateHigh`
- `FraudEvaluationLatencyHigh`
- `AlertNotificationLatencyHigh`
- `KafkaDlqTrafficDetected`
- `KafkaDlqReprocessFailed`

Este conjunto reduce tiempo de detección y evita depender solo de alertas técnicas genéricas.

[Imagen sugerida: captura de Grafana con una alerta SLO disparada y paneles de soporte para triage]

---

## Validación de comportamiento

### Carga reproducible con `k6`

El runner de carga está preparado para uso diario y para pruebas comparables:

- modos: `stress`, `spike`, `soak`, `smoke`,
- perfiles: `capacity-baseline`, `balanced`, `mostly-normal`, `fraud-focus`, `validation`, `chaos-5xx`, `custom`,
- soporte interactivo y no interactivo,
- validación fuerte de entradas antes de ejecutar.

Esto permite probar no solo throughput, sino también degradación controlada, presión de cola, comportamiento de errores y coherencia de decisiones de fraude.

[Imagen sugerida: resumen visual de resultados k6 (RPS, p95, error rate) comparando al menos dos perfiles]

### Pruebas automatizadas

La estrategia combina:

- unit tests,
- integration tests por servicio con Testcontainers (Kafka + PostgreSQL),
- e2e full pipeline con 6 escenarios (incluye carga mixta con asserts de error rate, P95 y materialización de alertas).

También hay workflows CI separados para build/unit, integración y e2e, con retries en e2e y artefactos de diagnóstico en fallo.

---

## Operación diaria y triage

Flujo operativo recomendado:

1. Confirmar salud de pipeline en dashboards de negocio.
2. Verificar si el cuello está en outbox publish, consumo, decisión o notificación.
3. Correlacionar por `traceId` en logs JSON.
4. Saltar a traza distribuida para secuencia exacta.

Scripts útiles del repositorio:

- `scripts/single-fraud-scenario.sh`
- `scripts/run-k6-stress.sh`
- `scripts/test-dlq.sh`
- `scripts/test-dlq-reprocess.sh`

[Imagen sugerida: tablero de triage con correlación por `traceId` entre logs, trazas y métricas DLQ]

---

## Garantías implementadas

La plataforma incluye garantías concretas en el tramo de escritura y publicación de eventos:

- menor riesgo de pérdida de eventos en fallos intermedios,
- mejor aislamiento entre path síncrono (API) y asíncrono (broker),
- mejor observabilidad del tramo de publicación.

---

## Deudas técnicas vigentes

| Área                     | Situación actual              | Riesgo                            | Próximo paso                                   |
| ------------------------ | ----------------------------- | --------------------------------- | ---------------------------------------------- |
| Contratos de eventos     | JSON sin governance formal    | Cambios incompatibles silenciosos | Versionado formal + contract tests en CI       |
| Esquema de BD            | `ddl-auto: update` aún en uso | Drift entre entornos              | Migraciones versionadas (Flyway/Liquibase)     |
| Seguridad API            | Endpoints sin authN/authZ     | Exposición operativa              | JWT/OAuth2 + rate limiting                     |
| Plataforma de despliegue | Foco en Docker Compose local  | Brecha frente a producción        | Perfil productivo (orquestación + autoscaling) |

También queda por madurar la estandarización fina de nomenclatura de métricas para simplificar mantenimiento de dashboards a largo plazo.

---

## Aprendizajes clave

1. Pasar a event-driven sin Outbox deja una brecha de consistencia demasiado costosa en el borde de entrada.
2. La idempotencia en consumidores no es opcional cuando hay `at-least-once` y paralelismo real.
3. DLQ sin reproceso y métricas dedicadas es deuda operativa disfrazada.
4. La observabilidad que realmente acelera triage combina métricas de negocio + logs estructurados + trazas.
5. Cargar el sistema con perfiles mixtos (no solo happy path) cambia la calidad de las decisiones de arquitectura.

---

## Cierre personal

Este proyecto me dejó una conclusión práctica: en arquitecturas orientadas a eventos, construir el flujo funcional es solo la mitad del trabajo; la otra mitad es hacerlo observable, recuperable y operable bajo presión.

Eso cambió mi criterio de diseño: ahora priorizo garantías, señales operativas y estrategias de recuperación desde el inicio.
