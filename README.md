# idempotent-transaction-processing

Java Spring Boot service for idempotent background transaction processing

# Idempotent Background Transaction Processing Service

A Java 17-compatible Spring Boot application that accepts transaction batches, queues ready events for background processing, and demonstrates request idempotency, business-transaction deduplication, per-account sequence ordering, transient retry simulation, and in-memory balance updates. This is an assessment/demo implementation, not a durable or distributed financial ledger.

## Problem and business use case

Payment and ledger integrations may redeliver requests, deliver events out of order, or encounter transient failures. Applying a duplicate financial effect can corrupt balances; processing events out of order can violate ledger ordering. This service demonstrates accepting requests, tracking their status, waiting for missing sequence numbers, and preventing duplicate effects within one running application instance.

## Features and assessment coverage

| Requirement | Implementation / evidence |
| --- | --- |
| Transaction fields and monetary precision | `model/TransactionRequest.java` uses `BigDecimal`; type is `model/TransactionType.java`. |
| Input validation | Jakarta Bean Validation on request and batch records; negative-amount test in `TransactionProcessingServiceTests`. |
| Request idempotency | `TransactionProcessingService` stores requests/results by `requestId`; same payload replays, changed payload returns 409. |
| Business transaction deduplication | The service reserves `transactionId`; a later different request becomes `DUPLICATE` without another balance effect. |
| Out-of-order events | Per-account `TreeMap` queue and sequence cursor in the service; tested by the out-of-order test. |
| Retry behavior and limit | `ConfiguredTransientFailureSimulator`, `transaction.simulated-failures`, and service `MAX_ATTEMPTS = 3`; retry/exhaustion tests. |
| Status lifecycle | `model/TransactionStatus.java`; `TransactionResult` includes current status and `statusHistory`. |
| Background batch processing | `TransactionProcessingConfiguration` defines four executor threads; the service queues ready events. |
| REST endpoints and summary | `controller/TransactionController.java`; service returns results and current account summary. |
| Atomicity in this process | A synchronized service monitor serializes ID reservation, results, sequence, and balance mutations in one JVM only. |
| JUnit 5 coverage | `src/test/java/com/example/fde_transaction_service/TransactionProcessingServiceTests.java` and application context test. |

## Technology and prerequisites

- Java 17 or newer. Maven compilation targets Java 17; use the included wrapper.
- Spring Boot 4.1.1, Spring MVC, Jakarta Bean Validation, Maven, and JUnit 5.
- No database, external queue, credentials, or other service is required.

## Project structure

```text
.
|-- docs/                         design and sample JSON
|-- src/main/java/.../controller/ REST mappings and exception responses
|-- src/main/java/.../model/      request, response, status, summary records
|-- src/main/java/.../service/    in-memory state, sequencing, executor, retries
|-- src/main/resources/           application configuration
|-- src/test/java/                JUnit 5 tests
|-- mvnw, mvnw.cmd                Maven wrapper
`-- pom.xml
```

Important classes: `TransactionController` owns the HTTP mappings; `TransactionProcessingService` owns request/business ID state, per-account sequencing, background dispatch, results, and balances; `TransactionProcessingConfiguration` creates the four-thread executor; `ConfiguredTransientFailureSimulator` reads the startup retry-failure setting; `TransactionExceptionHandler` maps selected service exceptions to HTTP errors. DTOs and enums are under `model`.

## Clone, build, test, and run

After creating your own remote repository:

```powershell
git clone https://github.com/<YOUR-USERNAME>/<YOUR-REPOSITORY>.git
Set-Location <YOUR-REPOSITORY>
```

On Windows PowerShell:

```powershell
java -version
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

The application listens at `http://localhost:8080`. To run the packaged jar:

```powershell
java -jar target\fde-transaction-service-0.0.1-SNAPSHOT.jar
```

On macOS/Linux use `./mvnw clean verify` and `./mvnw spring-boot:run`.

## HTTP API

### `POST /api/transactions/batch`

Accepts a non-empty JSON object with a `transactions` array. All fields shown are required. `transactionType` is `CREDIT` or `DEBIT`; `amount` is a non-negative `BigDecimal`; currency must match three uppercase letters; `sequenceNumber` must be positive.

```json
{
  "transactions": [
    {
      "transactionId": "txn-1001",
      "requestId": "req-1001",
      "accountId": "acct-42",
      "transactionType": "CREDIT",
      "amount": 125.50,
      "currency": "USD",
      "sequenceNumber": 1
    },
    {
      "transactionId": "txn-1002",
      "requestId": "req-1002",
      "accountId": "acct-42",
      "transactionType": "DEBIT",
      "amount": 25.00,
      "currency": "USD",
      "sequenceNumber": 2
    }
  ]
}
```

The response is an acceptance-time snapshot. The first result starts at `RECEIVED`; the second is `PENDING` until the worker advances sequence 1. Poll the result endpoint for completion.

Example immediate response:

```json
{
  "results": [
    {
      "transactionId": "txn-1001",
      "requestId": "req-1001",
      "status": "RECEIVED",
      "statusHistory": ["RECEIVED"],
      "attempts": 0,
      "message": "Transaction received",
      "amount": 125.50,
      "currency": "USD",
      "sequenceNumber": 1
    },
    {
      "transactionId": "txn-1002",
      "requestId": "req-1002",
      "status": "PENDING",
      "statusHistory": ["RECEIVED", "PENDING"],
      "attempts": 0,
      "message": "Waiting for sequence 1",
      "amount": 25.00,
      "currency": "USD",
      "sequenceNumber": 2
    }
  ]
}
```

### `GET /api/transactions/{requestId}`

Returns the latest result; an unknown request ID returns 404.

Example after successful processing:

```json
{
  "transactionId": "txn-1001",
  "requestId": "req-1001",
  "status": "PROCESSED",
  "statusHistory": ["RECEIVED", "PROCESSING", "PROCESSED"],
  "attempts": 1,
  "message": "Transaction processed",
  "amount": 125.50,
  "currency": "USD",
  "sequenceNumber": 1
}
```

### `GET /api/transactions/summary`

Returns the current count for every status, account count, and balances keyed by `accountId:currency`.

Example after the sample credit and debit complete:

```json
{
  "statusCounts": {
    "RECEIVED": 0,
    "PROCESSING": 0,
    "PROCESSED": 2,
    "DUPLICATE": 0,
    "PENDING": 0,
    "RETRY_PENDING": 0,
    "FAILED": 0
  },
  "accountCount": 1,
  "accountBalances": {"acct-42:USD": 100.50}
}
```

### Error responses and validation

Invalid JSON or request-body Bean Validation failures return HTTP 400 using Spring MVC's default body format; it is not customized. A service-level `InvalidTransactionException` returns `{"error":"invalid_transaction","message":"..."}` with 400. Changed-payload reuse of a `requestId` returns 409 with `{"error":"duplicate_request_id","message":"requestId is already associated with a different transaction: req-1001"}`. Unknown results return 404 with `{"error":"transaction_not_found","message":"No transaction found for requestId: missing-id"}`. Insufficient funds is a transaction result with `FAILED`, not an HTTP error.

## Idempotency, sequencing, and retries

`requestId` identifies the API submission. An equal replay returns the stored/current result; reusing it with a different record payload returns 409. Record equality includes `BigDecimal.equals`, so scale differs (`1.0` and `1.00` are unequal). `transactionId` identifies the business effect and is globally unique in this process. Another request ID with an already-seen business ID becomes `DUPLICATE` and is not queued for a second balance update.

Sequence numbers are positive, contiguous, account-wide, and start at 1 for each account. Currency balances are separate. A future sequence is retained as `PENDING`; missing numbers are never automatically skipped. When the next sequence arrives, the worker processes it and drains contiguous pending events. Insufficient-funds failures and retry exhaustion consume their sequence position, allowing later events to proceed. A duplicate business transaction is not queued and consumes no sequence position. A different event competing for an occupied sequence fails without replacing the queued event.

The four-thread executor processes ready events. State mutation is still protected by a single service monitor, serializing transactions across accounts. `transaction.simulated-failures` defaults to `0`; `1` fails the first attempt then succeeds, and `3` exhausts all three attempts. The setting applies to each transaction and is read at startup. Retries are immediate within the worker loop, not delayed; `RETRY_PENDING` appears in `statusHistory` between attempts. Exhaustion yields `FAILED`, preserves the balance, and advances the sequence.

## Statuses and observability

Implemented statuses: `RECEIVED`, `PROCESSING`, `PROCESSED`, `DUPLICATE`, `PENDING`, `RETRY_PENDING`, `FAILED`. The result exposes `statusHistory` and current status. Summary counts reflect only each request's current status. The application exposes no transaction-specific logs, metrics, tracing, audit trail, or custom health endpoints.

## Tests

Run `.\mvnw.cmd test` or `.\mvnw.cmd clean verify`. The tests cover duplicate business IDs, request replay/conflict, out-of-order processing, negative amounts, retry success/exhaustion, insufficient funds, asynchronous submission, queue-slot collision, and Spring context startup. The focused service tests do not exercise HTTP serialization or persistence.

## Limitations, assumptions, and production hardening

- All balances, sequences, pending events, IDs, results, and counts are volatile in-memory maps. Restart loses all of them; no recovery occurs.
- Synchronization coordinates one JVM only. It is not durable or safe across multiple app instances; the single monitor limits throughput.
- Batch submission is not an all-or-nothing transaction: earlier entries may have been accepted before a later service-level conflict. New accounts start at zero; there is no initial funding endpoint.
- Currency validation checks syntax only. No ISO registry, currency-specific precision, or rounding policy is implemented. Zero amounts are permitted.
- Production: persist transaction/idempotency state, account sequence cursors, pending work, and ledger effects in a database. Use unique constraints on request and transaction IDs, and commit idempotency, sequence, and balance/ledger effects in one database transaction. Use row locking or optimistic version checks rather than a process-wide lock.
- Use a durable queue/outbox for background work and restart recovery; persist retry state, classify retryable errors, apply bounded backoff/jitter, and define dead-letter/replay policies. Add authentication/authorization, rate limiting, audit, metrics, traces, health probes, and failure/recovery integration tests.

## AI assistance disclosure (verify and edit before submission)

Draft only: “GitHub Copilot assisted with implementation and documentation drafting, suggesting test cases and helping check consistency between the Java API and examples. I reviewed the generated work and ran the Maven test suite; the reported result is the output from my local run.” Keep only statements that accurately describe your use and verification.

See [design](docs/DESIGN.md), [sample requests](docs/sample-input.json), and [illustrative outputs](docs/sample-output.json).
