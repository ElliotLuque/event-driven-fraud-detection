package com.fraud.alert.service;

import com.fraud.alert.inbox.AlertInboxRepository;
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
    private final AlertInboxRepository alertInboxRepository;
    private final Duration processedEventRetention;
    private final Duration inboxEventRetention;

    public DataRetentionService(
            ProcessedEventRepository processedEventRepository,
            AlertInboxRepository alertInboxRepository,
            @Value("${app.data-retention.processed-event-ttl:PT1H}") Duration processedEventRetention,
            @Value("${app.data-retention.inbox-event-ttl:PT1H}") Duration inboxEventRetention
    ) {
        this.processedEventRepository = processedEventRepository;
        this.alertInboxRepository = alertInboxRepository;
        this.processedEventRetention = processedEventRetention;
        this.inboxEventRetention = inboxEventRetention;
    }

    @Scheduled(fixedRateString = "${app.data-retention.cleanup-interval:300000}")
    @Transactional
    public void purgeExpiredProcessedEvents() {
        purgeProcessedEvents();
        purgeInboxEvents();
    }

    private void purgeProcessedEvents() {
        Instant cutoff = Instant.now().minus(processedEventRetention);
        int deleted = processedEventRepository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired processed events older than {}", deleted, cutoff);
        }
    }

    private void purgeInboxEvents() {
        Instant cutoff = Instant.now().minus(inboxEventRetention);
        int deleted = alertInboxRepository.deleteProcessedBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired alert inbox events older than {}", deleted, cutoff);
        }
    }
}
