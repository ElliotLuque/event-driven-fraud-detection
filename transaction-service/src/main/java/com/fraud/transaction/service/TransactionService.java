package com.fraud.transaction.service;

import com.fraud.transaction.api.TransactionRequest;
import com.fraud.transaction.api.TransactionResponse;
import com.fraud.transaction.domain.Transaction;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.mapping.TransactionMapper;
import com.fraud.transaction.messaging.TransactionEventPublisher;
import com.fraud.transaction.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionService {

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
        Transaction transaction = transactionMapper.toTransaction(request, transactionId, now);

        transactionRepository.save(transaction);

        TransactionCreatedEvent event = transactionMapper.toCreatedEvent(transaction, UUID.randomUUID().toString(), now);
        transactionEventPublisher.publish(event);

        return transactionMapper.toResponse(transaction);
    }
}
