package com.fraud.transaction.service;

import com.fraud.transaction.api.TransactionRequest;
import com.fraud.transaction.api.TransactionResponse;
import com.fraud.transaction.domain.Transaction;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.mapping.TransactionMapper;
import com.fraud.transaction.messaging.TransactionEventPublisher;
import com.fraud.transaction.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionEventPublisher transactionEventPublisher;
    private final TransactionMapper transactionMapper;

    public TransactionService(
            TransactionRepository transactionRepository,
            TransactionEventPublisher transactionEventPublisher,
            TransactionMapper transactionMapper
    ) {
        this.transactionRepository = transactionRepository;
        this.transactionEventPublisher = transactionEventPublisher;
        this.transactionMapper = transactionMapper;
    }

    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request) {
        String transactionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        log.info("Creating transaction {} for userId={}, amount={} {}, merchantId={}, country={}",
                transactionId, request.userId(), request.amount(), request.currency(),
                request.merchantId(), request.country());

        Transaction transaction = transactionMapper.toTransaction(request, transactionId, now);
        transactionRepository.save(transaction);

        TransactionCreatedEvent event = transactionMapper.toCreatedEvent(transaction, UUID.randomUUID().toString(), now);
        transactionEventPublisher.publish(event);

        log.info("Transaction {} persisted and event published", transactionId);

        return transactionMapper.toResponse(transaction);
    }
}
