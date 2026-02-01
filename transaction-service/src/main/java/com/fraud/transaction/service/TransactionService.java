package com.fraud.transaction.service;

import com.fraud.transaction.api.TransactionRequest;
import com.fraud.transaction.api.TransactionResponse;
import com.fraud.transaction.domain.Transaction;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.messaging.TransactionEventPublisher;
import com.fraud.transaction.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final TransactionEventPublisher transactionEventPublisher;

    public TransactionService(
            TransactionRepository transactionRepository,
            TransactionEventPublisher transactionEventPublisher
    ) {
        this.transactionRepository = transactionRepository;
        this.transactionEventPublisher = transactionEventPublisher;
    }

    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request) {
        String transactionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Transaction transaction = new Transaction(
                transactionId,
                request.userId(),
                request.amount(),
                request.currency().toUpperCase(Locale.ROOT),
                request.merchantId(),
                request.country().toUpperCase(Locale.ROOT),
                request.paymentMethod(),
                now
        );

        transactionRepository.save(transaction);

        TransactionCreatedEvent event = new TransactionCreatedEvent(
                UUID.randomUUID().toString(),
                now,
                transaction.getId(),
                transaction.getUserId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getMerchantId(),
                transaction.getCountry(),
                transaction.getPaymentMethod()
        );
        transactionEventPublisher.publish(event);

        return new TransactionResponse(
                transaction.getId(),
                transaction.getUserId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getMerchantId(),
                transaction.getCountry(),
                transaction.getPaymentMethod(),
                transaction.getCreatedAt()
        );
    }
}
