package com.fraud.alert.service;

import com.fraud.alert.inbox.AlertInboxRepository;
import com.fraud.alert.inbox.AlertInboxStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class AlertInboxProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AlertInboxProcessingService.class);

    private final AlertInboxWorkUnitService alertInboxWorkUnitService;
    private final AlertInboxRepository alertInboxRepository;
    private final AlertMetrics alertMetrics;
    private final int batchSize;
    private final int workers;
    private final ExecutorService executorService;

    public AlertInboxProcessingService(
            AlertInboxWorkUnitService alertInboxWorkUnitService,
            AlertInboxRepository alertInboxRepository,
            AlertMetrics alertMetrics,
            @Value("${app.inbox.processor.batch-size:256}") int batchSize,
            @Value("${app.inbox.processor.workers:4}") int workers
    ) {
        this.alertInboxWorkUnitService = alertInboxWorkUnitService;
        this.alertInboxRepository = alertInboxRepository;
        this.alertMetrics = alertMetrics;
        this.batchSize = batchSize;
        this.workers = Math.max(1, workers);
        this.executorService = Executors.newFixedThreadPool(this.workers);
    }

    @Scheduled(
            fixedDelayString = "${app.inbox.processor.interval-ms:1}",
            initialDelayString = "${app.inbox.processor.initial-delay-ms:0}"
    )
    public void drainInbox() {
        int perWorkerBudget = Math.max(1, (int) Math.ceil((double) batchSize / workers));
        List<Future<Integer>> futures = new ArrayList<>(workers);

        for (int i = 0; i < workers; i++) {
            Callable<Integer> workerTask = () -> {
                int processed = 0;
                for (int j = 0; j < perWorkerBudget; j++) {
                    if (!alertInboxWorkUnitService.processSingleNext()) {
                        break;
                    }
                    processed++;
                }
                return processed;
            };
            futures.add(executorService.submit(workerTask));
        }

        int processed = 0;
        for (Future<Integer> future : futures) {
            try {
                processed += future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.error("alert_inbox_processing_interrupted", ex);
                break;
            } catch (ExecutionException ex) {
                log.error("alert_inbox_processing_failed", ex);
            }
        }

        long backlog = alertInboxRepository.countByStatus(AlertInboxStatus.RECEIVED);
        alertMetrics.recordInboxBacklog(backlog);

        if (processed > 0) {
            log.debug("alert_inbox_batch_processed count={}", processed);
        }
    }

    @PreDestroy
    public void stopExecutor() {
        executorService.shutdown();
    }
}
