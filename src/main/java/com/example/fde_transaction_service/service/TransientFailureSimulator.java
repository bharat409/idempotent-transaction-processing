package com.example.fde_transaction_service.service;

import com.example.fde_transaction_service.model.TransactionRequest;

@FunctionalInterface
public interface TransientFailureSimulator {
	boolean shouldFail(TransactionRequest request, int attempt);
}