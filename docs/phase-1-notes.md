# Phase 1 notes: core trading engine

Decisions, trade-offs, deviations from [spec.md](../spec.md) and how each Phase 1 acceptance criterion was verified.
For running the system see the [README](../README.md).

## Acceptance criteria (spec §9, Phase 1)

All verified on 2026-10-07, both by automated tests (`mvn clean verify`: 190 tests, 0 skipped) and by hand on a
fresh `docker compose` stack.

| Criterion | Automated evidence | Verified on the compose stack |
|---|---|---|
| `docker compose up` brings everything up healthy | – | Fresh volumes, `scripts/compose.sh up -d --build`: 11/11 services healthy after 50 s |
| Deposit → buy market → position with correct avg cost; cash reduced by exact fill amount | `FillProcessingIntegrationTest`, `PortfolioViewIntegrationTest`, `IdempotencyAcrossServicesTest` | 12.345 MSFT bought: notional 5,188.72695 → cash −518,873 cents exactly, avg cost 420.3100, reserved 0 |
| Limit buy below market rests, then fills when the simulated price crosses | `ExecutionFlowIntegrationTest.limitBuyBelowMarketRestsThenFillsWhenThePriceCrosses` | TSLA limit 250.42 (ask 250.67) rested, filled after ~134 s of real market time at 250.42 |
| Same `Idempotency-Key` 5× concurrently → exactly one order | `IdempotencyAcrossServicesTest` (5 requests over 2 order-service instances: 1 order, 1 reservation, 1 fill), `OrderFlowIntegrationTest`, `JdbcIdempotentExecutorTest` | 5 concurrent curls: 1 distinct order id, 1 row |
| Kill execution-service mid-stream, restart → no lost or duplicated executions; reconciliation exact | `ExecutionCrashRecoveryTest`: SIGKILL while ticks and orders flow, 140 orders, ~180 executions, every order terminal in all three services, execution count = portfolio fill count, balances to the cent | `docker kill -s KILL` mid-stream, 20 orders placed while it was down: 60/60 orders terminal and closed, 0 seq gaps/duplicates, 33 executions = 33 FILL ledger txns |
| Cancel-vs-fill race (Testcontainers, 1,000 iterations) always one terminal state with correct balances | `CancelVsFillRaceTest`: 1,000/1,000 consistent; outcomes in the last run FILLED 65, partial fill then CANCELED 72, CANCELED 863 | – |
| jqwik: random deposits/orders/cancels never produce negative cash or unbalanced txns | `LedgerProperties` (500 random sequences of up to 40 steps, incl. redelivered fills, checked against an independent model to the cent) + `NotionalProperties` | – |

**Final reconciliation** on the compose stack after all of the above: 161 ledger transactions, 388 entries,
`balanceMismatches = unbalancedTransactions = negativeBalances = positionMismatches = []`, `ok: true`.

## Money and correctness

- **Units.** Cash is `long` cents (`Money`); prices are `NUMERIC(19,4)` on a $0.01 tick (`Price`); quantities
  `NUMERIC(19,6)` (`Quantity`). JSON carries cents as integers and prices/quantities as strings, and float-to-int
  coercion is disabled (a test caught Jackson silently truncating `{"amountCents": 1.5}` to 1 cent).
- **Rounding** happens in exactly two places. Reservations round **up** (they must cover the fill). Fills round
  **half-even on the order's cumulative exact notional**, so the sum of partial fills equals the rounded total and
  never drifts (jqwik-tested). Market buys reserve `qty × ask × 1.05` and carry a `priceCap = ask × 1.05` rounded
  down; execution never fills above the cap, so a reservation can never be overspent (tested over 20,000 cases).
- **Ledger.** Double-entry; every transaction balances **per unit** (USD and each symbol separately), checked in
  code and by a deferred constraint trigger in Postgres; `ledger_entries` and `ledger_txns` are append-only
  (trigger); a CHECK keeps non-house balances ≥ 0. One HOUSE account per unit is the counterparty.
- **Optimistic locking, refined.** Debits of user balances use compare-and-set on `balances.version` with
  whole-transaction retry. Credits and house postings cannot break "never negative", so they are atomic increments
  under the row lock: optimistic retries on the single house row collapsed under 40 concurrent deposits.
- **Idempotency everywhere.** Mutating endpoints: `(account, Idempotency-Key)` claim/replay in Postgres with a
  Redis fast path in order-service; in-flight duplicates wait then replay (or 409); abandoned claims are taken
  over; failures release the key. `client_order_id` = the key, unique per account, so even the database refuses a
  second order. Every consumer records `processed_events` in the same transaction as its effects; execution ids
  are deterministic per (order, seq); ledger references are unique.
- **Outbox.** Every state change is published from an `outbox` table written in the same transaction. The polling
  relay takes a Postgres advisory lock per poll, so across instances exactly one relay publishes, in id order
  (per-key order preserved; 3 competing relays tested). At-least-once; consumers dedupe.

## Order flow and execution

- **Placement:** validate → insert `PENDING` → synchronous reserve (500 ms connect / 1 s read timeout, bulkhead of
  64, circuit breaker; business rejections never trip it) → one transaction `ACCEPTED` + `OrderAccepted`, or
  `REJECTED` (422 with order id and code). Unknown outcome → order stays `PENDING`, 503 + `Retry-After`, and a retry
  with the same key resumes the same order. A sweeper moves stale `PENDING` to `REJECTED` **first**
  (compare-and-set) and then releases, so it can never race the accept path.
- **Single writer per symbol:** one consumer thread per execution-service instance reads `orders.submitted` and
  `quotes.ticks`, both keyed by symbol with 12 partitions, with `RangeAssignor` so the owner of a symbol's orders
  also gets its ticks. `CancelRequested` travels on `orders.submitted`, so the writer always sees the order before
  its cancel, and cancel vs fill is decided by processing order: exactly one wins.
- **Fills:** market orders at the Redis quote, up to the displayed size, remainder on later ticks; a market-buy
  remainder priced above its cap is canceled (`COLLAR_EXCEEDED`). Limit orders fill when marketable (at the better
  quote price) or rest in a price-time-priority book. DAY orders expire at simulated 16:00.
- **Crash safety:** one transaction per event (executions, book rows, closed orders, outbox, processed marker); the
  in-memory book changes only after commit and is rebuilt from `resting_orders` on partition assignment; offsets
  are committed after the database; on failure partitions are rewound. Ticks older than 10 s are skipped so a
  restart never fills at stale prices (an earlier "seek to end" approach dropped the first tick after a restart).
- **Portfolio** applies executions in one transaction each: ledger FILL (cash and share legs), reservation
  consumption, positions read model, release of the remainder on the terminal event, activity row and
  `PortfolioUpdated` in the outbox. order-service follows the same topic for `filled_qty`, average price (exact
  running notional) and status, through the state machine (all 49 transitions tested).

## Deviations from the spec (all deliberate)

| Spec | Implemented | Why |
|---|---|---|
| Spring Data JPA | JPA in account-service; Spring JDBC (`JdbcClient`) elsewhere | Explicit, reviewable SQL on the money path; compare-and-set and `FOR UPDATE` are plain to read |
| Schemas in §5 | Extra columns/tables: `reservations`, `positions_view` cost basis precision, `activity`, `closed_orders`, `orders.{reserved_cash_cents, price_cap, cancel_requested_at, release_pending, fill_notional, last_exec_seq}`, `outbox.{event_id, topic, event_key}`, `idempotency_keys.{status_code, claimed_at}` | Each one is needed for a correctness rule above |
| Order state machine | Also `ACCEPTED → FILLED` and `PARTIALLY_FILLED → PARTIALLY_FILLED` | Implied by fills: a single fill completes an order; partials repeat |
| Validation | Also: limit prices in whole cents; MARKET orders are DAY with no limit price | Remove ambiguous combinations |
| `executions` keyed "by account" | Yes; `CancelRequested` also on `orders.submitted` | Per-account money order, per-symbol single writer |
| Ticks | Published directly, not through an outbox | Ephemeral market data superseded within ms; every state change still uses the outbox |
| FAST_CLOCK via config | Shared anchor in Redis (`market:clock-anchor`) set by the first service, or pinned with `MARKET_CLOCK_REAL_EPOCH` | All services agree on simulated time with no hand-synchronised env vars |
| Deposits | account-service calls portfolio synchronously (idempotent reference derived from the key) | Withdrawals must be able to fail with "insufficient funds" at request time |
| Funding cap | `FUNDING_MAX_AMOUNT_CENTS` = $1,000,000 per deposit/withdrawal | Guard against typos and overflow (default, configurable; spec is silent) |
| Phase 1 endpoints | Watchlists, `/api/portfolio/history`, `/api/movers` deferred to Phase 2 | Only the UI needs them (approved) |
| Observability | Prometheus/Grafana (datasource only), ECS JSON logs, `X-Request-Id` end to end; no Jaeger | Jaeger and dashboards are Phase 3 |
| Correctness module | `correctness-tests` runs the real service jars as processes | Shared `db/migration` classpaths prevent several services in one JVM; also makes `kill -9` real |

## Environment-specific workarounds

- **JDK 21 is enforced** by the build. The default JDK on the development machine was 27, whose class files
  ArchUnit cannot read (it silently fell back to a partial import and the money rule flickered).
- **Build output in `target.nosync/`.** The checkout lives on an iCloud-synced Desktop; iCloud duplicated build
  folders mid-build ("classes 2/") and broke compilation. iCloud ignores `*.nosync`. ArchUnit's "do not include
  tests" option is path-based, so the services use `ExcludeTestClasses` instead. Both go away if the repository
  moves off the Desktop.
- **`scripts/compose.sh`.** Docker's builder rejects the non-ASCII "—" in the folder name; the script builds through
  an ASCII symlink. Not needed for non-build compose commands, or after renaming the folder.

## Known limitations and Phase 3 candidates

- **Cold-start latency.** In the crash test, a reservation call to a freshly started portfolio-service
  occasionally exceeded order-service's 1 s read timeout (2 of ~140 placements in one run). The system behaves as
  designed (503, order kept `PENDING`, the retry with the same key resumes it) and the test client retries like a
  real one, reporting its retry count. Worth a warm-up or timeout review under load.
- **Throughput not yet measured.** Single consumer thread per execution-service instance (scale by instances up to
  the 12 partitions; per-partition threads possible); every deposit and fill takes the single HOUSE cash row lock
  (shard the house account if it shows up in profiles); no batch consumption for the ledger yet. Phase 3 measures
  these against §8.
- **Signing key.** Without `JWT_PRIVATE_KEY`, account-service generates an ephemeral key per start (fine for one
  local instance; clients just refresh after a restart).
- **Day P/L** is `qty × (last − previous close)`, so shares bought today count from the previous close.
- **Activity timestamps** are wall-clock time; executions carry simulated market time (a mixed sort order was a
  bug found when running the containers).
- **Simulated session:** with the default real-time clock a session lasts 6.5 hours; at `MARKET_CLOCK_SPEED=60`,
  6.5 minutes. Cent ticks make low-priced symbols move in visible steps (a limit 0.2 % under a $5 stock can take a
  while to cross).
