package com.fraud.transaction.api;

import com.fraud.transaction.domain.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record TransactionRequest(
        @NotBlank(message = "userId is required")
        String userId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be ISO-4217 code")
        String currency,

        @NotBlank(message = "merchantId is required")
        String merchantId,

        @NotBlank(message = "country is required")
        @Pattern(regexp = "^[A-Z]{2}$", message = "country must be ISO-3166 alpha-2")
        String country,

        @NotNull(message = "paymentMethod is required")
        PaymentMethod paymentMethod
) {
}
