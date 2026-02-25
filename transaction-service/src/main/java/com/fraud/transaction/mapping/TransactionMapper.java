package com.fraud.transaction.mapping;

import com.fraud.transaction.api.TransactionRequest;
import com.fraud.transaction.api.TransactionResponse;
import com.fraud.transaction.domain.Transaction;
import com.fraud.transaction.events.TransactionCreatedEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.Instant;
import java.util.Locale;

@Mapper(componentModel = "spring")
public interface TransactionMapper {

    @Mapping(target = "id", source = "transactionId")
    @Mapping(target = "currency", source = "request.currency", qualifiedByName = "normalizeCurrency")
    @Mapping(target = "country", source = "request.country", qualifiedByName = "normalizeCountry")
    @Mapping(target = "createdAt", source = "createdAt")
    Transaction toTransaction(TransactionRequest request, String transactionId, Instant createdAt);

    @Mapping(target = "transactionId", source = "id")
    TransactionResponse toResponse(Transaction transaction);

    @Mapping(target = "eventId", source = "eventId")
    @Mapping(target = "occurredAt", source = "occurredAt")
    @Mapping(target = "transactionId", source = "transaction.id")
    TransactionCreatedEvent toCreatedEvent(Transaction transaction, String eventId, Instant occurredAt);

    @Named("normalizeCurrency")
    default String normalizeCurrency(String currency) {
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    @Named("normalizeCountry")
    default String normalizeCountry(String country) {
        return country.trim().toUpperCase(Locale.ROOT);
    }
}
