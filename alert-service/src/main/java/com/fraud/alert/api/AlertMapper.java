package com.fraud.alert.api;

import com.fraud.alert.model.Alert;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Arrays;
import java.util.List;

@Mapper(componentModel = "spring")
public interface AlertMapper {

    @Mapping(target = "reasons", source = "reasons", qualifiedByName = "splitReasons")
    AlertResponse toResponse(Alert alert);

    @Named("splitReasons")
    default List<String> splitReasons(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return List.of();
        }
        return Arrays.stream(reasons.split(",")).toList();
    }
}
