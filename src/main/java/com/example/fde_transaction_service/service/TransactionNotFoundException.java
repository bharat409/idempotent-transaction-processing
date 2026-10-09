package com.example.fde_transaction_service.service;

public class TransactionNotFoundException extends RuntimeException {
	public TransactionNotFoundException(String requestId) {
		super("No transaction found for requestId: " + requestId);
	}
}