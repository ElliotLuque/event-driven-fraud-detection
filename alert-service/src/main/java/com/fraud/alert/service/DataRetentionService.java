package com.fraud.alert.service;

import com.fraud.alert.repository.ProcessedEventRepository;
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
    private final Duration processedEventRetention;

    public DataRetentionService(
            ProcessedEventRepository processedEventRepository,
            @Value("${app.data-retention.processed-event-ttl:PT1H}") Duration processedEventRetention
    ) {
        this.processedEventRepository = processedEventRepository;
        this.processedEventRetention = processedEventRetention;
    }

    @Scheduled(fixedRateString = "${app.data-retention.cleanup-interval:300000}")
    @Transactional
    public void purgeExpiredProcessedEvents() {
        Instant cutoff = Instant.now().minus(processedEventRetention);
        int deleted = processedEventRepository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired processed events older than {}", deleted, cutoff);
        }
    }
}
