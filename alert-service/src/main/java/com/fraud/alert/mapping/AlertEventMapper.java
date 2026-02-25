package com.fraud.alert.mapping;

import com.fraud.alert.events.FraudDetectedEvent;
import com.fraud.alert.model.Alert;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

@Mapper(componentModel = "spring", imports = UUID.class)
public interface AlertEventMapper {

    @Mapping(target = "id", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "transactionId", source = "event.transactionId")
    @Mapping(target = "userId", source = "event.userId")
    @Mapping(target = "riskScore", source = "event.riskScore")
    @Mapping(target = "reasons", source = "event.reasons", qualifiedByName = "joinReasons")
    @Mapping(target = "createdAt", source = "createdAt")
    Alert toAlert(FraudDetectedEvent event, Instant createdAt);

    @Named("joinReasons")
    default String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "UNSPECIFIED_RULE";
        }

        StringJoiner joiner = new StringJoiner(",");
        reasons.forEach(joiner::add);
        String joinedReasons = joiner.toString();
        return joinedReasons.isBlank() ? "UNSPECIFIED_RULE" : joinedReasons;
    }
}
