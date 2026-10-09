package com.example.fde_transaction_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.fde_transaction_service.model.TransactionRequest;
import com.example.fde_transaction_service.model.TransactionResult;
import com.example.fde_transaction_service.model.TransactionStatus;
import com.example.fde_transaction_service.model.TransactionType;
import com.example.fde_transaction_service.service.DuplicateRequestIdException;
import com.example.fde_transaction_service.service.InvalidTransactionException;
import com.example.fde_transaction_service.service.TransactionProcessingService;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class TransactionProcessingServiceTests {
	private ValidatorFactory validatorFactory;
	private Validator validator;

	@BeforeEach
	void setUp() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterEach
	void tearDown() {
		validatorFactory.close();
	}

	@Test
	void duplicateBusinessTransactionDoesNotChangeBalanceTwice() {
		TransactionProcessingService service = service((request, attempt) -> false);
		service.submit(request("business-1", "request-1", "account-1", TransactionType.CREDIT, "12.50", 1));

		TransactionResult duplicate = service.submit(
				request("business-1", "request-2", "account-1", TransactionType.CREDIT, "12.50", 2));

		assertEquals(TransactionStatus.DUPLICATE, duplicate.status());
		assertEquals(new BigDecimal("12.50"), service.getSummary().accountBalances().get("account-1:USD"));
	}

	@Test
	void outOfOrderTransactionWaitsAndProcessesWhenGapArrives() {
		TransactionProcessingService service = service((request, attempt) -> false);
		TransactionResult pending = service.submit(
				request("business-2", "request-2", "account-2", TransactionType.CREDIT, "5.00", 2));
		assertEquals(TransactionStatus.PENDING, pending.status());

		service.submit(request("business-1", "request-1", "account-2", TransactionType.CREDIT, "7.00", 1));

		assertEquals(TransactionStatus.PROCESSED, service.getResult("request-2").status());
		assertEquals(new BigDecimal("12.00"), service.getSummary().accountBalances().get("account-2:USD"));
	}

	@Test
	void negativeAmountIsRejected() {
		TransactionProcessingService service = service((request, attempt) -> false);
		assertThrows(InvalidTransactionException.class, () -> service.submit(
				request("business-1", "request-1", "account-1", TransactionType.CREDIT, "-1.00", 1)));
	}

	@Test
	void transientFailuresRetryAndStopAfterThreeAttempts() {
		TransactionProcessingService eventuallySucceeds = service((request, attempt) -> attempt < 3);
		TransactionResult processed = eventuallySucceeds.submit(
				request("business-1", "request-1", "account-1", TransactionType.CREDIT, "4.00", 1));
		assertEquals(TransactionStatus.PROCESSED, processed.status());
		assertEquals(3, processed.attempts());
		assertEquals(List.of(
				TransactionStatus.RECEIVED,
				TransactionStatus.PROCESSING,
				TransactionStatus.RETRY_PENDING,
				TransactionStatus.PROCESSING,
				TransactionStatus.RETRY_PENDING,
				TransactionStatus.PROCESSING,
				TransactionStatus.PROCESSED), processed.statusHistory());

		TransactionProcessingService alwaysFails = service((request, attempt) -> true);
		TransactionResult failed = alwaysFails.submit(
				request("business-2", "request-2", "account-2", TransactionType.CREDIT, "4.00", 1));
		assertEquals(TransactionStatus.FAILED, failed.status());
		assertEquals(3, failed.attempts());
		assertNull(alwaysFails.getSummary().accountBalances().get("account-2:USD"));
	}

	@Test
	void insufficientFundsFailsWithoutChangingBalance() {
		TransactionProcessingService service = service((request, attempt) -> false);
		TransactionResult failed = service.submit(
				request("business-1", "request-1", "account-1", TransactionType.DEBIT, "1.00", 1));

		assertEquals(TransactionStatus.FAILED, failed.status());
		assertEquals("Insufficient funds", failed.message());
		assertNull(service.getSummary().accountBalances().get("account-1:USD"));
	}

	@Test
	void successfulCreditFollowedByDebitUpdatesFinalBalance() {
		TransactionProcessingService service = service((request, attempt) -> false);
		TransactionResult credit = service.submit(
				request("business-credit", "request-credit", "account-1", TransactionType.CREDIT, "100.00", 1));
		TransactionResult debit = service.submit(
				request("business-debit", "request-debit", "account-1", TransactionType.DEBIT, "25.00", 2));

		assertEquals(TransactionStatus.PROCESSED, credit.status());
		assertEquals(TransactionStatus.PROCESSED, debit.status());
		assertEquals(new BigDecimal("75.00"), service.getSummary().accountBalances().get("account-1:USD"));
	}

	@Test
	void staleSequenceFailureStillReservesTransactionId() {
		TransactionProcessingService service = service((request, attempt) -> false);
		service.submit(request("business-first", "request-first", "account-1", TransactionType.CREDIT, "10.00", 1));

		TransactionResult stale = service.submit(
				request("business-stale", "request-stale", "account-1", TransactionType.CREDIT, "3.00", 1));
		TransactionResult correctedRetry = service.submit(
				request("business-stale", "request-corrected", "account-1", TransactionType.CREDIT, "3.00", 2));

		assertEquals(TransactionStatus.FAILED, stale.status());
		assertEquals(TransactionStatus.DUPLICATE, correctedRetry.status());
		assertEquals(new BigDecimal("10.00"), service.getSummary().accountBalances().get("account-1:USD"));
	}

	@Test
	void duplicateRequestIdIsIdempotentOnlyForSamePayload() {
		TransactionProcessingService service = service((request, attempt) -> false);
		TransactionRequest original = request(
				"business-1", "request-1", "account-1", TransactionType.CREDIT, "3.00", 1);
		TransactionResult first = service.submit(original);

		assertEquals(first, service.submit(original));
		assertThrows(DuplicateRequestIdException.class, () -> service.submit(
				request("business-2", "request-1", "account-1", TransactionType.CREDIT, "3.00", 2)));
	}

	@Test
	void concurrentDuplicateSubmissionsApplyOnlyOneBusinessEffect() throws Exception {
		TransactionProcessingService service = service((request, attempt) -> false);
		ExecutorService executor = Executors.newFixedThreadPool(6);
		try {
			List<Future<TransactionResult>> results = java.util.stream.IntStream.range(0, 6)
					.mapToObj(index -> executor.submit(() -> service.submit(request(
							"same-business", "request-" + index, "account-1",
							TransactionType.CREDIT, "9.00", 1))))
					.toList();
			results.forEach(result -> {
				try {
					result.get();
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
			});
		} finally {
			executor.shutdownNow();
		}

		assertEquals(new BigDecimal("9.00"), service.getSummary().accountBalances().get("account-1:USD"));
		assertEquals(1L, service.getSummary().statusCounts().get(TransactionStatus.PROCESSED));
	}

	@Test
	void submissionReturnsBeforeBackgroundProcessingCompletes() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch processingStarted = new CountDownLatch(1);
		TransactionProcessingService service = new TransactionProcessingService(
				validator, (request, attempt) -> {
					processingStarted.countDown();
					return false;
				}, executor);
		try {
			TransactionResult accepted = service.submit(
					request("business-1", "request-1", "account-1", TransactionType.CREDIT, "2.00", 1));
			assertEquals(TransactionStatus.RECEIVED, accepted.status());
			assertTrue(processingStarted.await(1, TimeUnit.SECONDS));
			assertEquals(TransactionStatus.PROCESSED, service.getResult("request-1").status());
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(1, TimeUnit.SECONDS);
		}
	}

	@Test
	void queuedEventCannotBeOverwrittenByAnotherTransactionAtSameSequence() {
		List<Runnable> queuedTasks = new ArrayList<>();
		TransactionProcessingService service = new TransactionProcessingService(
				validator, (request, attempt) -> false, queuedTasks::add);
		TransactionResult accepted = service.submit(
				request("business-1", "request-1", "account-1", TransactionType.CREDIT, "9.00", 1));

		TransactionResult collision = service.submit(
				request("business-2", "request-2", "account-1", TransactionType.CREDIT, "30.00", 1));
		TransactionResult correctedRetry = service.submit(
				request("business-2", "request-3", "account-1", TransactionType.CREDIT, "30.00", 2));
		assertEquals(TransactionStatus.RECEIVED, accepted.status());
		assertEquals(TransactionStatus.FAILED, collision.status());
		assertEquals(TransactionStatus.DUPLICATE, correctedRetry.status());

		queuedTasks.get(0).run();
		assertEquals(TransactionStatus.PROCESSED, service.getResult("request-1").status());
		assertEquals(new BigDecimal("9.00"), service.getSummary().accountBalances().get("account-1:USD"));
	}

	private TransactionProcessingService service(
			com.example.fde_transaction_service.service.TransientFailureSimulator simulator) {
		return new TransactionProcessingService(validator, simulator);
	}

	private TransactionRequest request(
			String transactionId,
			String requestId,
			String accountId,
			TransactionType type,
			String amount,
			long sequenceNumber) {
		return new TransactionRequest(
				transactionId, requestId, accountId, type, new BigDecimal(amount), "USD", sequenceNumber);
	}
}