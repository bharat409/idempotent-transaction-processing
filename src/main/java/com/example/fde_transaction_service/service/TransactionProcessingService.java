package com.example.fde_transaction_service.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.fde_transaction_service.model.ProcessingSummary;
import com.example.fde_transaction_service.model.TransactionBatchRequest;
import com.example.fde_transaction_service.model.TransactionBatchResponse;
import com.example.fde_transaction_service.model.TransactionRequest;
import com.example.fde_transaction_service.model.TransactionResult;
import com.example.fde_transaction_service.model.TransactionStatus;
import com.example.fde_transaction_service.model.TransactionType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

@Service
public class TransactionProcessingService {
	private static final int MAX_ATTEMPTS = 3;

	private final Validator validator;
	private final TransientFailureSimulator failureSimulator;
	private final Executor processingExecutor;
	private final Map<String, TransactionRequest> requestsById = new HashMap<>();
	private final Map<String, TransactionResult> resultsByRequestId = new HashMap<>();
	private final Map<String, String> requestIdByTransactionId = new HashMap<>();
	private final Map<String, AccountState> accounts = new HashMap<>();

	@Autowired
	public TransactionProcessingService(
			Validator validator,
			TransientFailureSimulator failureSimulator,
			@Qualifier("transactionProcessingExecutor") Executor processingExecutor) {
		this.validator = validator;
		this.failureSimulator = failureSimulator;
		this.processingExecutor = processingExecutor;
	}

	public TransactionProcessingService(Validator validator, TransientFailureSimulator failureSimulator) {
		this(validator, failureSimulator, Runnable::run);
	}

	public synchronized TransactionBatchResponse submitBatch(TransactionBatchRequest batch) {
		if (batch == null || batch.transactions() == null || batch.transactions().isEmpty()) {
			throw new InvalidTransactionException("transactions must contain at least one transaction");
		}
		for (TransactionRequest request : batch.transactions()) {
			submit(request);
		}
		List<TransactionResult> results = batch.transactions().stream()
				.map(request -> resultsByRequestId.get(request.requestId()))
				.toList();
		return new TransactionBatchResponse(results);
	}

	public synchronized TransactionResult submit(TransactionRequest request) {
		validate(request);
		TransactionRequest priorRequest = requestsById.get(request.requestId());
		if (priorRequest != null) {
			if (!priorRequest.equals(request)) {
				throw new DuplicateRequestIdException(request.requestId());
			}
			return resultsByRequestId.get(request.requestId());
		}

		requestsById.put(request.requestId(), request);
		updateResult(request, TransactionStatus.RECEIVED, 0, "Transaction received");
		if (requestIdByTransactionId.containsKey(request.transactionId())) {
			updateResult(request, TransactionStatus.DUPLICATE, 0,
					"Business transaction has already been submitted");
			return resultsByRequestId.get(request.requestId());
		}

		requestIdByTransactionId.put(request.transactionId(), request.requestId());
		AccountState account = accounts.computeIfAbsent(request.accountId(), ignored -> new AccountState());
		long expectedSequence = account.lastSequenceNumber + 1;
		if (request.sequenceNumber() > expectedSequence) {
			if (account.pendingBySequence.containsKey(request.sequenceNumber())) {
				updateResult(request, TransactionStatus.FAILED, 0,
						"A different transaction already occupies this account sequence");
			} else {
				account.pendingBySequence.put(request.sequenceNumber(), request);
				updateResult(request, TransactionStatus.PENDING, 0,
						"Waiting for sequence " + expectedSequence);
			}
			return resultsByRequestId.get(request.requestId());
		}
		if (request.sequenceNumber() < expectedSequence) {
			updateResult(request, TransactionStatus.FAILED, 0,
					"Sequence number has already been passed for this account");
			return resultsByRequestId.get(request.requestId());
		}
		if (account.pendingBySequence.containsKey(request.sequenceNumber())) {
			updateResult(request, TransactionStatus.FAILED, 0,
					"A different transaction already occupies this account sequence");
			return resultsByRequestId.get(request.requestId());
		}

		account.pendingBySequence.put(request.sequenceNumber(), request);
		processingExecutor.execute(() -> processReadyTransactions(request.accountId()));
		return resultsByRequestId.get(request.requestId());
	}

	public synchronized TransactionResult getResult(String requestId) {
		TransactionResult result = resultsByRequestId.get(requestId);
		if (result == null) {
			throw new TransactionNotFoundException(requestId);
		}
		return result;
	}

	public synchronized ProcessingSummary getSummary() {
		Map<TransactionStatus, Long> statusCounts = new EnumMap<>(TransactionStatus.class);
		for (TransactionStatus status : TransactionStatus.values()) {
			statusCounts.put(status, 0L);
		}
		resultsByRequestId.values().forEach(result ->
				statusCounts.compute(result.status(), (status, count) -> count + 1));
		Map<String, BigDecimal> balances = new HashMap<>();
		accounts.forEach((accountId, account) -> account.balancesByCurrency.forEach((currency, balance) ->
				balances.put(accountId + ":" + currency, balance)));
		return new ProcessingSummary(Map.copyOf(statusCounts), accounts.size(), Map.copyOf(balances));
	}

	private void validate(TransactionRequest request) {
		if (request == null) {
			throw new InvalidTransactionException("transaction must not be null");
		}
		Set<ConstraintViolation<TransactionRequest>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			String message = violations.stream()
					.map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
					.sorted()
					.collect(Collectors.joining("; "));
			throw new InvalidTransactionException(message);
		}
	}

	private void processInSequence(AccountState account, TransactionRequest request) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			updateResult(request, TransactionStatus.PROCESSING, attempt, "Processing transaction");
			if (failureSimulator.shouldFail(request, attempt)) {
				if (attempt < MAX_ATTEMPTS) {
					updateResult(request, TransactionStatus.RETRY_PENDING, attempt,
							"Transient failure; retry scheduled");
					continue;
				}
				updateResult(request, TransactionStatus.FAILED, attempt,
						"Transient failure after maximum attempts");
				account.lastSequenceNumber = request.sequenceNumber();
				return;
			}

			BigDecimal balance = account.balancesByCurrency.getOrDefault(request.currency(), BigDecimal.ZERO);
			if (request.transactionType() == TransactionType.DEBIT && balance.compareTo(request.amount()) < 0) {
				updateResult(request, TransactionStatus.FAILED, attempt, "Insufficient funds");
			} else {
				BigDecimal updatedBalance = request.transactionType() == TransactionType.CREDIT
						? balance.add(request.amount())
						: balance.subtract(request.amount());
				account.balancesByCurrency.put(request.currency(), updatedBalance);
				updateResult(request, TransactionStatus.PROCESSED, attempt, "Transaction processed");
			}
			account.lastSequenceNumber = request.sequenceNumber();
			return;
		}
	}

	private synchronized void processReadyTransactions(String accountId) {
		AccountState account = accounts.get(accountId);
		if (account == null) {
			return;
		}
		TransactionRequest next;
		while ((next = account.pendingBySequence.remove(account.lastSequenceNumber + 1)) != null) {
			processInSequence(account, next);
		}
	}

	private void updateResult(TransactionRequest request, TransactionStatus status, int attempts, String message) {
		TransactionResult previous = resultsByRequestId.get(request.requestId());
		List<TransactionStatus> statusHistory = previous == null
				? new ArrayList<>()
				: new ArrayList<>(previous.statusHistory());
		if (statusHistory.isEmpty() || statusHistory.get(statusHistory.size() - 1) != status) {
			statusHistory.add(status);
		}
		resultsByRequestId.put(request.requestId(), new TransactionResult(
				request.transactionId(), request.requestId(), status, List.copyOf(statusHistory), attempts, message,
				request.amount(), request.currency(), request.sequenceNumber()));
	}

	private static final class AccountState {
		private long lastSequenceNumber;
		private final Map<String, BigDecimal> balancesByCurrency = new HashMap<>();
		private final TreeMap<Long, TransactionRequest> pendingBySequence = new TreeMap<>();
	}
}