package com.fraud.transaction.service;

import com.fraud.transaction.api.TransactionRequest;
import com.fraud.transaction.api.TransactionResponse;
import com.fraud.transaction.domain.PaymentMethod;
import com.fraud.transaction.domain.Transaction;
import com.fraud.transaction.events.TransactionCreatedEvent;
import com.fraud.transaction.mapping.TransactionMapper;
import com.fraud.transaction.outbox.TransactionOutboxService;
import com.fraud.transaction.repository.TransactionRepository;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionOutboxService transactionOutboxService;

    @Mock
    private TransactionMetrics transactionMetrics;

    @Mock
    private Tracer tracer;

    @Spy
    private TransactionMapper transactionMapper = Mappers.getMapper(TransactionMapper.class);

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void createTransactionShouldNormalizeValuesAndPublishMatchingEvent() {
        TransactionRequest request = new TransactionRequest(
                "user-1",
                new BigDecimal("49.90"),
                " usd ",
                "MRC-200",
                " us ",
                PaymentMethod.CARD
        );

        TransactionResponse response = transactionService.createTransaction(request);

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        Transaction storedTransaction = transactionCaptor.getValue();

        ArgumentCaptor<TransactionCreatedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCreatedEvent.class);
        verify(transactionOutboxService).enqueue(eventCaptor.capture());
        TransactionCreatedEvent publishedEvent = eventCaptor.getValue();

        assertEquals("USD", storedTransaction.getCurrency());
        assertEquals("US", storedTransaction.getCountry());
        assertEquals("USD", response.currency());
        assertEquals("US", response.country());
        assertEquals("USD", publishedEvent.currency());
        assertEquals("US", publishedEvent.country());

        assertNotNull(response.transactionId());
        assertNotNull(publishedEvent.eventId());
        assertNotNull(publishedEvent.traceId());
        assertTrue(publishedEvent.traceId().matches("[0-9a-f]{32}"));
        assertEquals(response.transactionId(), storedTransaction.getId());
        assertEquals(response.transactionId(), publishedEvent.transactionId());
        assertEquals(storedTransaction.getCreatedAt(), response.createdAt());
        assertEquals(storedTransaction.getCreatedAt(), publishedEvent.occurredAt());
        assertEquals(PaymentMethod.CARD, publishedEvent.paymentMethod());
    }
}
