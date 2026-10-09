package com.example.fde_transaction_service.model;

import java.math.BigDecimal;
import java.util.Map;

public record ProcessingSummary(
		Map<TransactionStatus, Long> statusCounts,
		int accountCount,
		Map<String, BigDecimal> accountBalances) {
}