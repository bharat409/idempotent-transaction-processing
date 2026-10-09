package com.example.fde_transaction_service.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.fde_transaction_service.model.ProcessingSummary;
import com.example.fde_transaction_service.model.TransactionBatchRequest;
import com.example.fde_transaction_service.model.TransactionBatchResponse;
import com.example.fde_transaction_service.model.TransactionResult;
import com.example.fde_transaction_service.service.TransactionProcessingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class TransactionController {
	private final TransactionProcessingService transactionService;

	public TransactionController(TransactionProcessingService transactionService) {
		this.transactionService = transactionService;
	}

	@PostMapping("/transactions/batch")
	public TransactionBatchResponse submitBatch(@Valid @RequestBody TransactionBatchRequest batch) {
		return transactionService.submitBatch(batch);
	}

	@GetMapping("/transactions/{requestId}")
	public TransactionResult getTransaction(@PathVariable String requestId) {
		return transactionService.getResult(requestId);
	}

	@GetMapping("/transactions/summary")
	public ProcessingSummary getSummary() {
		return transactionService.getSummary();
	}
}