# idempotent-transaction-processing

## Idempotent Background Transaction Processing Service

A Java 17-compatible Spring Boot application that accepts transaction batches, queues ready events for background processing, and demonstrates request idempotency, business-transaction deduplication, per-account sequence ordering, transient retry simulation, and in-memory balance updates. This is an assessment/demo implementation, not a durable or distributed financial ledger.

## Problem and business use case

Payment and ledger integrations may redeliver requests, deliver events out of order, or encounter transient failures. Applying a duplicate financial effect can corrupt balances; processing events out of order can violate ledger ordering. This service demonstrates accepting requests, tracking their status, waiting for missing sequence numbers, and preventing duplicate effects within one running application instance.

## Features and assessment coverage

| Requirement | Implementation / evidence |
| --- | --- |
| Transaction fields and monetary precision | `model/TransactionRequest.java` uses `BigDecimal`; supported types are `CREDIT` and `DEBIT` in `model/TransactionType.java`. `REVERSAL` is not implemented. |
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

The original assessment brief requires a `transactionType` field but does not specify a `REVERSAL` type. If the assessment rubric expects reversal semantics, that feature and its tests are missing. `CREDIT` and `DEBIT` are supported; tests cover a successful credit followed by a successful debit and the resulting balance, as well as an insufficient-funds debit.

## Technology and prerequisites

- Java 17 or newer. Maven compilation targets Java 17.
- Spring Boot 4.1.1, Spring MVC, Jakarta Bean Validation, Maven, and JUnit 5.
- The included Maven Wrapper is used below, so a separate Maven installation is not required.
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

Clone the repository once:

```text
git clone https://github.com/bharat409/idempotent-transaction-processing.git
```

On Windows PowerShell:

```powershell
Set-Location idempotent-transaction-processing
java -version
.\mvnw.cmd clean verify
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

On macOS/Linux:

```sh
cd idempotent-transaction-processing
java -version
./mvnw clean verify
./mvnw test
./mvnw spring-boot:run
```

`clean verify` compiles, tests, and packages the application; `test` runs the test suite by itself. The application listens at `http://localhost:8080`. To run the packaged jar on Windows, use `java -jar target\fde-transaction-service-0.0.1-SNAPSHOT.jar`; on macOS/Linux use `java -jar target/fde-transaction-service-0.0.1-SNAPSHOT.jar`.

## HTTP API

### `POST /api/transactions/batch`

Accepts a non-empty JSON object with a `transactions` array. All fields shown are required. `transactionType` is `CREDIT` or `DEBIT`; `amount` is a non-negative `BigDecimal`; currency must match three uppercase letters; `sequenceNumber` must be positive. Record-level validation failures and request-ID conflicts are isolated: the response contains one result per input item, in input order, an invalid item has status `FAILED`, and subsequent valid items are still submitted. Malformed JSON or a missing/empty `transactions` array rejects the whole request with HTTP 400.

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

The batch endpoint returns an acceptance-time snapshot, not a live view. For new entries in one batch, the service lock prevents the background executor from advancing them before that snapshot is assembled: a valid next-sequence transaction is `RECEIVED`, while a future sequence is `PENDING`. Statuses can vary with how quickly background processing starts and advances between requests; for example, replaying an existing request ID returns its current stored result. Background processing may continue after the snapshot is returned. Poll `GET /api/transactions/{requestId}` for the latest status and history.

Illustrative immediate response for this two-entry batch in fresh in-memory state (derived from the current synchronized service flow; not a captured or guaranteed runtime response):

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

Malformed JSON or an invalid batch envelope (missing/empty `transactions`) returns HTTP 400 using Spring MVC's default response format. A parsed record-level validation failure, null record, or changed-payload request-ID conflict returns that item as `FAILED` in the HTTP 200 batch response; the batch continues with later items. The failure message explains the rejected item. Rejections are retained in the in-memory summary. A validation rejection with a nonblank request ID not already associated with an accepted transaction can be queried by ID; for a request-ID conflict, `GET` continues to return the original accepted result, while the conflict rejection remains in the batch response and summary. Unknown results return HTTP 404 with `{"error":"transaction_not_found","message":"No transaction found for requestId: missing-id"}`. Insufficient funds is also a transaction result with `FAILED`, not an HTTP error.

## Idempotency, sequencing, and retries

`requestId` identifies the API submission. An equal replay returns the stored/current result; reusing it with a different record payload becomes an item-level `FAILED` result in a batch and does not stop other items. Record equality includes `BigDecimal.equals`, so scale differs (`1.0` and `1.00` are unequal). `transactionId` identifies the business effect and is globally unique in this process. Another request ID with an already-seen business ID becomes `DUPLICATE` and is not queued for a second balance update.

The service reserves `transactionId` after request validation but before checking the account sequence. Therefore, a valid request that later fails because its sequence is stale or already occupied still reserves that business ID. Replaying the same request ID/payload returns its stored `FAILED` result; submitting a corrected sequence under a different request ID is treated as `DUPLICATE`. This is current implementation behavior, not a durable idempotency policy.

Sequence numbers are positive, contiguous, account-wide, and start at 1 for each account. Currency balances are separate. A future sequence is retained as `PENDING`; missing numbers are never automatically skipped. When the next sequence arrives, the worker processes it and drains contiguous pending events. Insufficient-funds failures and retry exhaustion consume their sequence position, allowing later events to proceed. A duplicate business transaction is not queued and consumes no sequence position. A different event competing for an occupied sequence fails without replacing the queued event.

The four-thread executor processes ready events. State mutation is still protected by a single service monitor, serializing transactions across accounts. `transaction.simulated-failures` defaults to `0`; `1` fails the first attempt then succeeds, and `3` exhausts all three attempts. The setting applies to each transaction and is read at startup. Retries are immediate within the worker loop, not delayed; `RETRY_PENDING` appears in `statusHistory` between attempts. Exhaustion yields `FAILED`, preserves the balance, and advances the sequence. The result remains inspectable in memory, but there is no manual replay endpoint; restart loses it.

## Statuses and observability

Implemented statuses: `RECEIVED`, `PROCESSING`, `PROCESSED`, `DUPLICATE`, `PENDING`, `RETRY_PENDING`, `FAILED`. The result exposes `statusHistory` and current status. Summary counts include registered results and batch record rejections. A rejection without a usable request ID can be inspected in the batch response and summary but not polled by ID. The application exposes no transaction-specific logs, metrics, tracing, audit trail, or custom health endpoints; result and summary endpoints provide a basic processing report.

## Tests

Run `.\mvnw.cmd test` or `.\mvnw.cmd clean verify`. The tests cover duplicate business IDs, request replay/conflict, out-of-order processing, negative amounts and per-record batch isolation, retry success/exhaustion, successful credit followed by debit with a final-balance assertion, insufficient funds, stale/conflicting sequence ID reservation, asynchronous submission, queue-slot collision, and Spring context startup. Null/missing fields, malformed JSON, and HTTP binding/error responses do not have dedicated tests. The focused service tests do not exercise HTTP serialization or persistence.

## Limitations, assumptions, and production hardening

- All balances, sequences, pending events, IDs, results, and counts are volatile in-memory maps. Restart loses all of them; no recovery occurs.
- Synchronization coordinates one JVM only. It is not durable or safe across multiple app instances; the single monitor limits throughput.
- Batch submission is not an all-or-nothing transaction. Expected record-level validation failures and request-ID conflicts are isolated, but malformed JSON/top-level envelope errors or unexpected runtime failures can reject the whole call after earlier records were accepted. New accounts start at zero; there is no initial funding endpoint.
- Currency validation checks syntax only. No ISO registry, currency-specific precision, or rounding policy is implemented. Zero amounts are permitted.
- Production: persist transaction/idempotency state, account sequence cursors, pending work, and ledger effects in a database. Use unique constraints on request and transaction IDs, and commit idempotency, sequence, and balance/ledger effects in one database transaction. Use row locking or optimistic version checks rather than a process-wide lock.
- Use a durable queue/outbox for background work and restart recovery; persist retry state, classify retryable errors, apply bounded backoff/jitter, and define dead-letter/replay policies. Add authentication/authorization, rate limiting, audit, metrics, traces, health probes, and failure/recovery integration tests.

## AI assistance disclosure (verify and edit before submission)

Draft only: “GitHub Copilot assisted with implementation and documentation drafting, suggesting test cases and helping check consistency between the Java API and examples. I reviewed the generated work and ran the Maven test suite; the reported result is the output from my local run.” Keep only statements that accurately describe your use and verification.

See [design](docs/DESIGN.md), [sample requests](docs/sample-input.json), and [illustrative outputs](docs/sample-output.json).
