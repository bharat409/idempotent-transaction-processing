package com.example.fde_transaction_service.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record TransactionRequest(
		@NotBlank String transactionId,
		@NotBlank String requestId,
		@NotBlank String accountId,
		@NotNull TransactionType transactionType,
		@NotNull @PositiveOrZero BigDecimal amount,
		@NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
		@Positive long sequenceNumber) {
}