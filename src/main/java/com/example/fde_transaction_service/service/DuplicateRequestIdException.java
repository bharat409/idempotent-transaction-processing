package com.example.fde_transaction_service.service;

public class DuplicateRequestIdException extends RuntimeException {
	public DuplicateRequestIdException(String requestId) {
		super("requestId is already associated with a different transaction: " + requestId);
	}
}