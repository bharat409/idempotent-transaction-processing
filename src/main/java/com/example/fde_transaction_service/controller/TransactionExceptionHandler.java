package com.example.fde_transaction_service.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.fde_transaction_service.model.ApiError;
import com.example.fde_transaction_service.service.DuplicateRequestIdException;
import com.example.fde_transaction_service.service.InvalidTransactionException;
import com.example.fde_transaction_service.service.TransactionNotFoundException;

@RestControllerAdvice
public class TransactionExceptionHandler {
	@ExceptionHandler(InvalidTransactionException.class)
	public ResponseEntity<ApiError> handleInvalidTransaction(InvalidTransactionException exception) {
		return ResponseEntity.badRequest().body(new ApiError("invalid_transaction", exception.getMessage()));
	}

	@ExceptionHandler(DuplicateRequestIdException.class)
	public ResponseEntity<ApiError> handleDuplicateRequestId(DuplicateRequestIdException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiError("duplicate_request_id", exception.getMessage()));
	}

	@ExceptionHandler(TransactionNotFoundException.class)
	public ResponseEntity<ApiError> handleTransactionNotFound(TransactionNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiError("transaction_not_found", exception.getMessage()));
	}
}