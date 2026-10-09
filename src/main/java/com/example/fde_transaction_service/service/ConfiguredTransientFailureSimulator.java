package com.example.fde_transaction_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.fde_transaction_service.model.TransactionRequest;

@Component
public class ConfiguredTransientFailureSimulator implements TransientFailureSimulator {
	private final int simulatedFailures;

	public ConfiguredTransientFailureSimulator(
			@Value("${transaction.simulated-failures:0}") int simulatedFailures) {
		this.simulatedFailures = Math.max(0, simulatedFailures);
	}

	@Override
	public boolean shouldFail(TransactionRequest request, int attempt) {
		return attempt <= simulatedFailures;
	}
}