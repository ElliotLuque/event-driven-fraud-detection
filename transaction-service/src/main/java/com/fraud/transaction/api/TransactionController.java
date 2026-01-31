package com.fraud.transaction.api;

import com.fraud.transaction.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(@RequestBody @Valid TransactionRequest request) {
        return transactionService.createTransaction(request);
    }

    @PostMapping("/webhooks/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransactionFromWebhook(@RequestBody @Valid TransactionRequest request) {
        return transactionService.createTransaction(request);
    }
}
