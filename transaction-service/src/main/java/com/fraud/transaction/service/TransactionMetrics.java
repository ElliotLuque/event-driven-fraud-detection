package com.fraud.transaction.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class TransactionMetrics {

    private final MeterRegistry meterRegistry;
    private final Timer transactionPersistTimer;
    private final Timer transactionOutboxEnqueueTimer;
    private final Timer transactionPublishTimer;

    public TransactionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.transactionPersistTimer = Timer.builder("transaction_persist_latency")
                .description("Latency for persisting a transaction")
                .register(meterRegistry);
        this.transactionOutboxEnqueueTimer = Timer.builder("transaction_outbox_enqueue_latency")
                .description("Latency for enqueuing transaction.created events to outbox")
                .register(meterRegistry);
        this.transactionPublishTimer = Timer.builder("transaction_event_publish_latency")
                .description("Latency for publishing transaction.created events")
                .register(meterRegistry);
    }

    public void recordTransactionReceived(String channel) {
        meterRegistry.counter("transactions_received", "channel", channel).increment();
    }

    public void recordTransactionPersisted(long durationMs) {
        transactionPersistTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordTransactionEventEnqueued(String outcome, long durationMs) {
        meterRegistry.counter("transaction_events_enqueued", "outcome", outcome).increment();
        transactionOutboxEnqueueTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordTransactionEventPublished(String outcome, long durationMs) {
        meterRegistry.counter("transaction_events_published", "outcome", outcome).increment();
        transactionPublishTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }
}
