package com.fraud.detection.service;

import com.fraud.detection.repository.ProcessedEventRepository;
import com.fraud.detection.repository.UserTransactionHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final UserTransactionHistoryRepository historyRepository;
    private final Duration processedEventRetention;
    private final Duration transactionHistoryRetention;

    public DataRetentionService(
            ProcessedEventRepository processedEventRepository,
            UserTransactionHistoryRepository historyRepository,
            @Value("${app.data-retention.processed-event-ttl:PT1H}") Duration processedEventRetention,
            @Value("${app.data-retention.transaction-history-ttl:PT1H}") Duration transactionHistoryRetention
    ) {
        this.processedEventRepository = processedEventRepository;
        this.historyRepository = historyRepository;
        this.processedEventRetention = processedEventRetention;
        this.transactionHistoryRetention = transactionHistoryRetention;
    }

    @Scheduled(fixedRateString = "${app.data-retention.cleanup-interval:300000}")
    @Transactional
    public void purgeExpiredData() {
        purgeProcessedEvents();
        purgeTransactionHistory();
    }

    private void purgeProcessedEvents() {
        Instant cutoff = Instant.now().minus(processedEventRetention);
        int deleted = processedEventRepository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired processed events older than {}", deleted, cutoff);
        }
    }

    private void purgeTransactionHistory() {
        Instant cutoff = Instant.now().minus(transactionHistoryRetention);
        int deleted = historyRepository.deleteByOccurredAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired transaction history records older than {}", deleted, cutoff);
        }
    }
}
