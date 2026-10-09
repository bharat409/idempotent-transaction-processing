package com.example.fde_transaction_service.model;

import java.math.BigDecimal;
import java.util.List;

public record TransactionResult(
		String transactionId,
		String requestId,
		TransactionStatus status,
		List<TransactionStatus> statusHistory,
		int attempts,
		String message,
		BigDecimal amount,
		String currency,
		long sequenceNumber) {
}