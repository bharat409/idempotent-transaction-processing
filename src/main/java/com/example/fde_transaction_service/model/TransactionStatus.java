package com.example.fde_transaction_service.model;

public enum TransactionStatus {
	RECEIVED,
	PROCESSING,
	PROCESSED,
	DUPLICATE,
	PENDING,
	RETRY_PENDING,
	FAILED
}