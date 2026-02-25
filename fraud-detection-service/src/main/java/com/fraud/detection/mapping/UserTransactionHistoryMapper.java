package com.fraud.detection.mapping;

import com.fraud.detection.events.TransactionCreatedEvent;
import com.fraud.detection.model.UserTransactionHistory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring")
public interface UserTransactionHistoryMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "transactionId", source = "event.transactionId")
    @Mapping(target = "userId", source = "event.userId")
    @Mapping(target = "amount", source = "event.amount")
    @Mapping(target = "currency", source = "event.currency")
    @Mapping(target = "merchantId", source = "event.merchantId")
    @Mapping(target = "country", source = "event.country")
    @Mapping(target = "occurredAt", source = "occurredAt")
    UserTransactionHistory toHistory(TransactionCreatedEvent event, Instant occurredAt);
}
