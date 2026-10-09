package com.example.fde_transaction_service.model;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransactionBatchRequest(
		@NotNull @Size(min = 1) List<@Valid TransactionRequest> transactions) {
}