package com.example.fde_transaction_service.service;

public class InvalidTransactionException extends RuntimeException {
	public InvalidTransactionException(String message) {
		super(message);
	}
}