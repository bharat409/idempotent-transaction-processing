package com.example.fde_transaction_service.model;

import java.util.List;

public record TransactionBatchResponse(List<TransactionResult> results) {
}