package com.fraud.transaction.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.transaction.domain.PaymentMethod;
import com.fraud.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TransactionController.class)
@Import(ApiExceptionHandler.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    @Test
    void createTransactionShouldReturnCreatedResponse() throws Exception {
        TransactionRequest request = new TransactionRequest(
                "user-1",
                new BigDecimal("120.50"),
                "USD",
                "MRC-101",
                "US",
                PaymentMethod.CARD
        );

        TransactionResponse response = new TransactionResponse(
                "tx-1",
                "user-1",
                new BigDecimal("120.50"),
                "USD",
                "MRC-101",
                "US",
                PaymentMethod.CARD,
                Instant.parse("2026-01-01T10:00:00Z")
        );

        when(transactionService.createTransaction(any(TransactionRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("tx-1"))
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.country").value("US"))
                .andExpect(jsonPath("$.paymentMethod").value("CARD"));

        verify(transactionService).createTransaction(any(TransactionRequest.class));
    }

    @Test
    void createTransactionFromWebhookShouldReturnCreatedResponse() throws Exception {
        TransactionRequest request = new TransactionRequest(
                "user-webhook",
                new BigDecimal("99.99"),
                "USD",
                "MRC-202",
                "US",
                PaymentMethod.TRANSFER
        );

        TransactionResponse response = new TransactionResponse(
                "tx-webhook",
                "user-webhook",
                new BigDecimal("99.99"),
                "USD",
                "MRC-202",
                "US",
                PaymentMethod.TRANSFER,
                Instant.parse("2026-01-01T10:00:00Z")
        );

        when(transactionService.createTransaction(any(TransactionRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/webhooks/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("tx-webhook"))
                .andExpect(jsonPath("$.userId").value("user-webhook"));

        verify(transactionService).createTransaction(any(TransactionRequest.class));
    }

    @Test
    void createTransactionShouldReturnValidationErrorsForInvalidPayload() throws Exception {
        String invalidPayload = """
                {
                  "userId": "",
                  "amount": 0,
                  "currency": "usd",
                  "merchantId": "",
                  "country": "U",
                  "paymentMethod": null
                }
                """;

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.userId").value("userId is required"))
                .andExpect(jsonPath("$.errors.amount").value("amount must be greater than zero"))
                .andExpect(jsonPath("$.errors.currency").value("currency must be ISO-4217 code"))
                .andExpect(jsonPath("$.errors.merchantId").value("merchantId is required"))
                .andExpect(jsonPath("$.errors.country").value("country must be ISO-3166 alpha-2"))
                .andExpect(jsonPath("$.errors.paymentMethod").value("paymentMethod is required"));
    }
}
