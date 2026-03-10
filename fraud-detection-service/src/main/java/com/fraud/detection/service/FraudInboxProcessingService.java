package com.fraud.detection.service;

import com.fraud.detection.inbox.FraudInboxRepository;
import com.fraud.detection.inbox.FraudInboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class FraudInboxProcessingService {

    private static final Logger log = LoggerFactory.getLogger(FraudInboxProcessingService.class);

    private final FraudInboxWorkUnitService fraudInboxWorkUnitService;
    private final FraudInboxRepository fraudInboxRepository;
    private final FraudDetectionMetrics fraudDetectionMetrics;
    private final int batchSize;
    private final int workers;
    private final int claimSize;
    private final int backlogSampleIntervalRuns;
    private final ExecutorService executorService;
    private long drainRuns;

    public FraudInboxProcessingService(
            FraudInboxWorkUnitService fraudInboxWorkUnitService,
            FraudInboxRepository fraudInboxRepository,
            FraudDetectionMetrics fraudDetectionMetrics,
            @Value("${app.inbox.processor.batch-size:200}") int batchSize,
            @Value("${app.inbox.processor.workers:8}") int workers,
            @Value("${app.inbox.processor.claim-size:16}") int claimSize,
            @Value("${app.inbox.processor.backlog-sample-interval-runs:50}") int backlogSampleIntervalRuns
    ) {
        this.fraudInboxWorkUnitService = fraudInboxWorkUnitService;
        this.fraudInboxRepository = fraudInboxRepository;
        this.fraudDetectionMetrics = fraudDetectionMetrics;
        this.batchSize = batchSize;
        this.workers = Math.max(1, workers);
        this.claimSize = Math.max(1, claimSize);
        this.backlogSampleIntervalRuns = Math.max(1, backlogSampleIntervalRuns);
        this.executorService = Executors.newFixedThreadPool(this.workers);
        this.drainRuns = 0;
    }

    @Scheduled(
            fixedDelayString = "${app.inbox.processor.interval-ms:10}",
            initialDelayString = "${app.inbox.processor.initial-delay-ms:0}"
    )
    public void drainInbox() {
        int perWorkerBudget = Math.max(1, (int) Math.ceil((double) batchSize / workers));
        List<Future<Integer>> futures = new ArrayList<>(workers);

        for (int i = 0; i < workers; i++) {
            Callable<Integer> workerTask = () -> {
                int processedByWorker = 0;
                int remaining = perWorkerBudget;
                while (remaining > 0) {
                    int currentClaimSize = Math.min(claimSize, remaining);
                    int processedBatch = fraudInboxWorkUnitService.processNextBatch(currentClaimSize);
                    if (processedBatch <= 0) {
                        break;
                    }
                    processedByWorker += processedBatch;
                    remaining -= processedBatch;
                    if (processedBatch < currentClaimSize) {
                        break;
                    }
                }
                return processedByWorker;
            };
            futures.add(executorService.submit(workerTask));
        }

        int processed = 0;
        for (Future<Integer> future : futures) {
            try {
                processed += future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.error("fraud_inbox_processing_interrupted", ex);
                break;
            } catch (ExecutionException ex) {
                log.error("fraud_inbox_processing_failed", ex);
            }
        }

        drainRuns++;
        if (processed > 0 || drainRuns % backlogSampleIntervalRuns == 0) {
            long backlog = fraudInboxRepository.countByStatus(FraudInboxStatus.RECEIVED);
            fraudDetectionMetrics.recordInboxBacklog(backlog);
        }

        if (processed > 0) {
            log.debug("fraud_inbox_batch_processed count={}", processed);
        }
    }

    @PreDestroy
    public void stopExecutor() {
        executorService.shutdown();
    }
}
