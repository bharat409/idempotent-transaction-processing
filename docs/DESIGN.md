# Design Explanation

## Scope

This design documents the current source and separates implemented behavior from future production improvements. The service is an in-memory, single-JVM assessment implementation. It is not durable, distributed-safe, or production-ready.

## Architecture

```mermaid
flowchart LR
    Client[HTTP client] --> Controller[TransactionController]
    Controller --> Validation[Bean Validation]
    Validation --> Service[TransactionProcessingService]
    Service --> State[(In-memory maps and account state)]
    Service --> Executor[Four-thread processing executor]
    Executor --> Service
    Service --> Simulator[ConfiguredTransientFailureSimulator]
    Controller --> Advice[TransactionExceptionHandler]
    Service --> Summary[ProcessingSummary]
```

### Implemented responsibilities

- `TransactionController` maps batch submission, request-result lookup, and processing summary.
- `TransactionRequest` and `TransactionBatchRequest` define Jakarta Bean Validation constraints. `TransactionResult` returns current status, history, attempt count, message, and request fields.
- `TransactionProcessingService` owns request/business-ID maps, result state, per-account sequence cursors, pending events, currency balances, and in-memory batch rejection records. Public mutations and worker processing are synchronized.
- `TransactionProcessingConfiguration` provides a fixed four-thread executor. Ready work is queued after the service records its initial state.
- `ConfiguredTransientFailureSimulator` reads `transaction.simulated-failures` at startup and fails a fixed number of initial attempts for every transaction.
- `TransactionExceptionHandler` maps selected service errors to `ApiError`; Spring MVC handles request-body conversion and validation errors.

## Processing flow

```mermaid
sequenceDiagram
    participant C as Client
    participant R as REST Controller
    participant S as Transaction Service
    participant M as In-memory State
    participant E as Executor

    C->>R: POST /api/transactions/batch
    R->>R: Deserialize and validate
    R->>S: Submit each transaction
    S->>M: Record request and RECEIVED result
    alt Existing requestId
        S->>M: Return existing result or reject changed payload
    else Existing transactionId
        S->>M: Record DUPLICATE, do not queue effect
    else Sequence gap
        S->>M: Store PENDING event by sequence
    else Next sequence is ready
        S->>M: Reserve sequence slot
        S->>E: Queue account processing
    end
    S-->>R: Acceptance-time result snapshot
    R-->>C: Batch response
    E->>S: Process consecutive ready events
    S->>M: Update status, retries, cursor, and balance under monitor
    C->>R: GET /api/transactions/{requestId}
    R->>S: Read latest result
    S-->>R: Current status and statusHistory
    R-->>C: Result response
```

The controller validates the batch envelope (non-empty `transactions` list), while the service validates each parsed item independently. Expected record validation failures and changed-payload request-ID conflicts become per-item `FAILED` results; they do not abort iteration over later items. Malformed JSON and invalid top-level envelopes still fail the complete HTTP request before item processing.

## Lifecycle and states

An accepted request begins `RECEIVED`. A missing predecessor leaves it `PENDING`. Ready work is queued to the executor and becomes `PROCESSING`. Success becomes `PROCESSED`. Transient simulated failures record `RETRY_PENDING` between attempts; retry exhaustion, insufficient funds, stale sequence, sequence-slot collision, record validation errors, and changed-payload request-ID conflicts become `FAILED`. A previously reserved business ID under another request ID becomes `DUPLICATE`.

The `status` field and summary represent the latest state; `statusHistory` captures transitions. The POST response is a snapshot taken before the worker can acquire the service monitor, so a ready event normally returns `RECEIVED`. Poll `GET /api/transactions/{requestId}` for completion. `RETRY_PENDING` is short-lived: attempts run immediately in the same worker invocation, with no delay/backoff.

## Idempotency and financial-effect protection

`requestId` identifies a caller submission. Equal record payload replay returns the current stored result; a different payload with the same request ID raises a conflict (HTTP 409). Java record equality applies, including scale-sensitive `BigDecimal.equals`: `1.0` and `1.00` are not equal payload fields.

`transactionId` identifies the business effect. It is globally reserved in the current process, not scoped per account. A different `requestId` with an existing transaction ID receives `DUPLICATE` and is not placed on the account queue. That prevents a second balance effect during the current process lifetime.

The reservation is recorded after request validation and before account sequence checks. A request that is subsequently marked `FAILED` for a stale or occupied sequence still reserves its `transactionId`; retrying the corrected event with another request ID will therefore return `DUPLICATE`. Replaying the original request ID and equal payload returns the stored failed result. This ordering is a limitation of the current implementation and should be revisited for a production correction/replay policy.

Synchronized service methods serialize the in-memory reservation, result update, account sequencing, and balance mutation against other threads in this JVM. This is not an ACID transaction: process failure can erase state, and multiple instances do not share a lock or ID registry.

## Sequence handling

Each account starts at sequence 1. The next acceptable sequence is `lastSequenceNumber + 1`; sequence numbers are account-wide, while balances are separately held per currency. A higher sequence is stored in a `TreeMap` as `PENDING`. Missing sequence numbers are never skipped automatically. When the next expected transaction is queued, the worker processes it and drains any immediately consecutive pending events.

Insufficient-funds failures and retry exhaustion consume their sequence position so a later pending event can continue. An event behind the cursor fails. A different event cannot replace an event already occupying the same pending/ready slot. Business duplicates are not queued and consume no sequence position.

## Retry behavior

`MAX_ATTEMPTS` is three. `transaction.simulated-failures=0` disables injected failures; `1` fails the first attempt and then succeeds; `3` exhausts all attempts. The setting applies to every transaction and is read at startup. Each transient failure records `RETRY_PENDING`; after exhaustion, result status is `FAILED`, balance remains unchanged, and the account cursor advances.

This deterministic simulator is demonstration behavior, not production failure handling. Real retry policies should classify errors, use bounded backoff with jitter, and persist attempts and dead-letter state.

An exhausted transaction remains traceable through its result and status summary while the process is running, but the implementation has no manual retry/recovery endpoint. Restart loses both the failure record and the ability to inspect or recover it.

## Concurrency, errors, and observability

The executor has four worker threads, but workers contend on one synchronized service state, serializing processing across accounts while the monitor is held. This is simple for the assessment but limits throughput. The ready-sequence occupancy check protects against concurrent submissions overwriting a queued event at the same sequence.

Malformed JSON or invalid top-level batch envelopes receive HTTP 400 from Spring MVC or service handling. Parsed record validation errors and changed-payload request-ID conflicts are isolated into per-item `FAILED` results within the HTTP 200 batch response. Unknown result lookups return HTTP 404. Business outcomes such as insufficient funds are also transaction results with status `FAILED`, not HTTP error responses. Exception-advice mappings for 400/409 remain available if those service exceptions escape another call path.

The API provides status history, attempts, messages, status counts, account count, and balances. Batch rejections are kept in memory and included in the failed count. A validation rejection can be queried when it has a nonblank request ID not already associated with an accepted result; a request-ID conflict remains available in the batch response/summary while lookup returns the original accepted result. A rejection with no usable ID is available only in the batch response and summary. There are no transaction-specific application logs, metrics, traces, audit records, or custom health endpoints; the result and summary APIs are the basic processing report.

## In-memory trade-offs and restart behavior

The in-memory maps/lists avoid infrastructure and make the behavior easy to demonstrate, but they are volatile and unbounded. Restart loses balances, sequence positions, pending events, idempotency IDs, results, batch rejections, and summary counts. No work is recovered. Separate instances can accept the same IDs and apply the effect independently. Batch submission is not all-or-nothing; malformed top-level requests or unexpected runtime failures can occur after earlier entries have been accepted.

## Future production architecture (not implemented)

```mermaid
flowchart LR
    API[API service] --> DB[(Relational database)]
    DB --> Outbox[Transactional outbox]
    Outbox --> Queue[(Durable message broker)]
    Queue --> Worker[Background worker]
    Worker --> DB
    DB --> Constraints[Unique requestId and transactionId constraints]
```

Persist request/idempotency records, transaction lifecycle, account cursors, pending work, and ledger entries. Enforce unique constraints for request and business IDs. In one database transaction, lock or version-check the account row, validate sequence, apply the ledger effect, advance its cursor, and update idempotency/result state. Use a transactional outbox to publish durable work; workers should be idempotent and maintain an inbox/deduplication record. Persist retry and dead-letter state.

With that architecture, restart can reload pending work and resume safely. The current app has no recovery: all in-memory state disappears on restart.

## Assumptions and decisions

- Sequences are positive, contiguous, account-wide, and start at 1.
- New account/currency balances start at zero; there is no funding endpoint.
- Zero amount is accepted; negative amount is rejected.
- Currency validation checks only `[A-Z]{3}`; no ISO registry, scale, or rounding is applied.
- Business transaction IDs are globally unique in this process.
- Only `CREDIT` and `DEBIT` are implemented; `REVERSAL` is not supported. The original assessment brief names the transaction type field but does not define reversal semantics.
- Tests cover a successful credit followed by a successful debit and the resulting balance, as well as insufficient-funds debit behavior.
- Parsed record-level validation failures and request-ID payload conflicts return per-item `FAILED` results; malformed JSON and invalid batch envelopes reject the whole HTTP request.
- Missing sequences are not skipped; failed in-order transactions advance the cursor.
- HTTP request Bean Validation happens before controller invocation. Batch business processing is not an all-or-nothing transaction.
- Atomicity/concurrency guarantees apply only inside one running JVM. The database and broker architecture above is a future improvement.