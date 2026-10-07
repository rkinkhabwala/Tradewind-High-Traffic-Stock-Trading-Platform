# Tradewind — High-Traffic Stock Trading Platform (Robinhood-style)

> Working name: **Tradewind**. Rename freely.
> **Paper trading only**: simulated money, simulated market data, no real broker or exchange connectivity.
> Build target: local-only (Docker Compose), designed and load-tested as if it were production at scale.
> Implementation: Claude Code in VS Code, phase by phase. Do not start a phase until the previous phase's acceptance criteria pass.

---

## 1. Goal

A Robinhood-style app where users fund an account with play money, watch live stock prices, place market and limit orders, and see their portfolio update in real time. The point of the project is **high-traffic engineering**: streaming quotes to many concurrent clients, taking orders at high throughput without ever losing or double-counting money, and proving it with load tests.

### The three hard problems (everything else is CRUD)
1. **Market data fan-out**: thousands of price ticks per second pushed to 10k+ WebSocket clients, each subscribed to different symbols.
2. **Order correctness under load**: no double fills, no overspending buying power, no lost orders, even with retries, crashes, and duplicate messages.
3. **Burst traffic**: market open (9:30 ET) and "meme stock" spikes where one symbol gets most of the traffic.

### Non-goals
- Real brokerage, real money, KYC, regulatory reporting, options, crypto, margin.
- Mobile apps (web client only).
- Cloud deployment (scale is simulated locally with multiple instances + load tests).

---

## 2. Tech stack

| Concern | Choice |
|---|---|
| Language / build | Java 21 (virtual threads enabled), Maven multi-module |
| Framework | Spring Boot 3.x (Web, WebFlux for streaming, Security, Data JPA, Actuator) |
| API gateway | Spring Cloud Gateway (routing, JWT validation, rate limiting) |
| Messaging | Apache Kafka (KRaft mode, no ZooKeeper) |
| Databases | PostgreSQL 16 (one logical DB per service) + Flyway |
| Cache / pub-sub | Redis (latest quotes, rate limits, idempotency keys, WebSocket fan-out across nodes) |
| Real-time | WebSocket (STOMP not required; plain JSON frames) |
| Resilience | Resilience4j (circuit breakers, bulkheads, retries, time limiters) |
| Observability | Micrometer → Prometheus + Grafana; OpenTelemetry tracing → Jaeger; structured JSON logs |
| Load testing | Gatling (Java DSL) — order flow + WebSocket clients |
| Frontend (Phase 2) | React + Vite + TypeScript, TanStack Query, lightweight-charts for price charts |
| Testing | JUnit 5, Testcontainers (Postgres, Kafka, Redis), RestAssured, Awaitility |

Pin every Docker image to an exact tag in docker-compose.yml. Never use `latest`.

---

## 3. Architecture

```
                     ┌────────────────────┐
  Browser ──HTTPS──▶ │  gateway           │  JWT, rate limits, routing
  Browser ──WS────▶  │  (Spring Cloud GW) │
                     └──┬─────┬──────┬────┘
                        │     │      │
          ┌─────────────┘     │      └──────────────┐
          ▼                   ▼                     ▼
   ┌─────────────┐    ┌──────────────┐     ┌────────────────┐
   │ account-svc │    │  order-svc   │     │  stream-svc    │ (N instances)
   │ users, auth │    │ validate,    │     │ WebSocket fan- │
   │ funding     │    │ reserve BP,  │     │ out of quotes, │
   └──────┬──────┘    │ outbox       │     │ order updates  │
          │           └──────┬───────┘     └───────▲────────┘
          │                  │ orders.submitted    │ Redis pub/sub
          ▼                  ▼                     │
   ┌──────────────────────── Kafka ────────────────┴──────────┐
   │ quotes.ticks │ orders.* │ executions │ ledger.events     │
   └───┬──────────────┬───────────────┬───────────────┬───────┘
       │              ▼               ▼               ▼
┌──────┴───────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ marketdata-  │ │ execution-svc│ │ portfolio-svc│ │ notification │
│ svc (price   │ │ fills orders │ │ ledger,      │ │ (Phase 4)    │
│ simulator)   │ │ vs quotes,   │ │ positions,   │ └──────────────┘
└──────────────┘ │ limit book   │ │ buying power │
                 └──────────────┘ └──────────────┘
```

### Maven modules
- `tradewind-common` — event schemas (Java records + JSON), money/quantity types, idempotency utilities, Kafka config.
- `gateway` — Spring Cloud Gateway.
- `account-service` — registration, login, JWT issue/refresh, simulated deposits/withdrawals.
- `marketdata-service` — price simulator, publishes ticks, serves quote snapshots and candles.
- `order-service` — order intake API, validation, buying-power reservation, transactional outbox.
- `execution-service` — fills orders against the current quote; holds resting limit orders per symbol.
- `portfolio-service` — double-entry ledger, positions, cash, buying power; the source of truth for money.
- `stream-service` — WebSocket server; horizontally scaled.
- `load-tests` — Gatling simulations.
- `web/` (Phase 2) — React app (sibling folder, not a Maven module).

---

## 4. Money and correctness rules (non-negotiable)

- **Money is never `double`.** Store cash as `BIGINT` cents (or `NUMERIC(19,4)` if sub-cent pricing is needed); in Java use `long` cents or `BigDecimal` with explicit scale and `RoundingMode.HALF_EVEN`.
- **Quantities** support fractional shares: `NUMERIC(19,6)`.
- **Double-entry ledger**: every money or share movement is a balanced set of ledger entries (debits = credits). Balances are derived from the ledger and cached in a `balances` table updated in the same transaction. A nightly-style reconciliation job (on demand) re-sums the ledger and must match the cached balances exactly.
- **Idempotency everywhere**:
  - Every mutating API call requires an `Idempotency-Key` header. Store `(user_id, key) → response` in Postgres (with Redis as a fast path) for 24h. A retry returns the original response, never creates a second order.
  - Every Kafka consumer is idempotent: it records processed `event_id`s (or uses a natural unique key like `execution_id`) in the same DB transaction as its side effects.
- **Transactional outbox**: services never "write DB then publish to Kafka" as two steps. They write the event to an `outbox` table in the same transaction; a relay publishes it (polling relay in v1; Debezium CDC optional in Phase 4).
- **Buying power reservation**: placing a buy order reserves `qty × price × (1 + collar)` (market orders use a 5% collar over the ask) before it is accepted. The reservation is released or converted on fill/cancel/reject. Sells reserve shares the same way. A user can never spend reserved money twice.
- **Single writer per symbol**: execution-service consumes `orders.submitted` partitioned by `symbol`, so one consumer thread owns a symbol's limit orders at a time. No distributed locks on the hot path.
- **Ordering per account**: `orders.*` events are keyed by `symbol`; ledger events keyed by `account_id`, so each account's money movements are processed in order.

---

## 5. Domain model (per service)

**account-service**
```
users(id, email UNIQUE, password_hash, created_at)
accounts(id, user_id FK UNIQUE, status[ACTIVE|LOCKED], created_at)
refresh_tokens(id, user_id, token_hash, expires_at, revoked)
```

**order-service**
```
orders(id UUID, account_id, client_order_id, symbol, side[BUY|SELL],
       type[MARKET|LIMIT], time_in_force[DAY|GTC], qty, limit_price NULL,
       status[PENDING|ACCEPTED|PARTIALLY_FILLED|FILLED|CANCELED|REJECTED|EXPIRED],
       filled_qty, avg_fill_price, reject_reason, created_at, updated_at,
       UNIQUE(account_id, client_order_id))
idempotency_keys(account_id, key, request_hash, response_json, created_at, PK(account_id, key))
outbox(id, aggregate_id, event_type, payload_json, created_at, published_at NULL)
```
Order state machine (enforced in code, tested exhaustively):
```
PENDING → ACCEPTED → PARTIALLY_FILLED → FILLED
PENDING → REJECTED
ACCEPTED|PARTIALLY_FILLED → CANCELED | EXPIRED
```

**execution-service**
```
resting_orders(order_id, symbol, side, limit_price, remaining_qty, account_id, created_at)
executions(id UUID, order_id, symbol, side, qty, price, executed_at, UNIQUE(order_id, seq))
processed_events(event_id PK, processed_at)
```
In-memory per-symbol limit-order index (sorted by price, then time), rebuilt from `resting_orders` on startup/rebalance.

**portfolio-service**
```
ledger_accounts(id, account_id, type[CASH|CASH_RESERVED|POSITION|POSITION_RESERVED|HOUSE], symbol NULL)
ledger_entries(id, txn_id, ledger_account_id, amount, created_at)   -- sum per txn_id = 0
ledger_txns(id, type[DEPOSIT|WITHDRAWAL|RESERVE|RELEASE|FILL], reference_id UNIQUE, created_at)
balances(ledger_account_id PK, amount, version)                      -- optimistic locking
positions_view(account_id, symbol, qty, avg_cost)                    -- read model
processed_events(event_id PK, processed_at)
```

**marketdata-service**
```
symbols(symbol PK, name, sector, base_price, volatility)
candles_1m(symbol, bucket_start, open, high, low, close, volume, PK(symbol, bucket_start))
```
Latest quotes live in Redis: `quote:{symbol}` → `{bid, ask, last, ts}`.

---

## 6. Core flows

### 6.1 Market data
- Simulator generates prices for ~500 symbols using geometric Brownian motion with per-symbol volatility, bid/ask spread, and occasional jumps. Configurable tick rate (default: each symbol ticks 1–10×/sec → ~2,500 ticks/sec total).
- Market hours simulated (9:30–16:00 ET), plus a `FAST_CLOCK` mode for demos and tests.
- Each tick: update Redis `quote:{symbol}`, publish to Kafka `quotes.ticks` (keyed by symbol), and fold into 1-minute candles.
- "Hot symbol" mode: config flag makes one symbol tick 100×/sec with high volatility, to reproduce meme-stock spikes.
- Optional: replay mode from a CSV of historical prices instead of the simulator.

### 6.2 Streaming to clients (stream-service)
- Client connects `wss://.../ws` with JWT, then sends `{"op":"subscribe","symbols":["AAPL","TSLA"]}`.
- Each stream-service instance consumes `quotes.ticks` (each instance in its **own consumer group**, so every instance sees every tick) and keeps an in-memory map `symbol → Set<session>`.
- **Conflation**: per session per symbol, send at most one update per 250ms (latest value wins). A slow client never makes the server buffer unbounded ticks.
- **Backpressure**: bounded per-session send queue; if it overflows, drop stale quotes first; if the client stays slow for >10s, disconnect with a close code so the client reconnects.
- User-specific events (order status, fills, buying power changes) arrive via Kafka → portfolio/order services → Redis pub/sub channel `user:{userId}` → whichever stream-service instance holds that user's socket.
- Heartbeat ping every 15s; clients auto-reconnect with exponential backoff + jitter and re-subscribe.

### 6.3 Placing an order
1. `POST /api/orders` with `Idempotency-Key`. Gateway applies per-user rate limit (default 10 orders/sec, burst 20).
2. order-service validates: symbol exists, market open (or GTC limit), qty > 0, limit price within 50% of last price (fat-finger check).
3. order-service calls portfolio-service `POST /internal/reservations` (sync, with timeout + circuit breaker) to reserve buying power or shares. Insufficient → order REJECTED, returned immediately.
4. In one DB transaction: insert order (ACCEPTED), store idempotency response, write `OrderAccepted` to outbox. Respond `202` with order id. Target p99 < 100ms at load.
5. Outbox relay publishes to `orders.submitted`.
6. execution-service:
   - MARKET: fill immediately at current ask (buy) or bid (sell) from Redis, with simulated partial fills for orders larger than displayed size.
   - LIMIT: fill immediately if marketable; otherwise add to the in-memory resting index. On every `quotes.ticks` for that symbol, check the top of the resting index and fill crossing orders.
   - Publishes `ExecutionReported` (unique `execution_id`) to `executions`.
7. portfolio-service consumes `executions`: in one transaction, posts ledger entries (reserved cash → house, house shares → position, release unused reservation), updates balances and positions, records `event_id`. Publishes `PortfolioUpdated`.
8. order-service consumes `executions` to update `filled_qty`, `avg_fill_price`, status.
9. stream-service pushes order status + portfolio change to the user's socket.

### 6.4 Cancel
- `DELETE /api/orders/{id}` → order-service emits `CancelRequested` → execution-service removes the resting order if still unfilled and emits `OrderCanceled` (or reports it already filled) → portfolio releases the reservation.
- Race between cancel and fill must resolve to exactly one outcome; covered by tests.

### 6.5 End of day
- At simulated 16:00, execution-service expires remaining DAY orders → `OrderExpired` → reservations released.

---

## 7. REST API (public, through gateway)

### Auth & account
- `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`
- `POST /api/account/deposits` `{amountCents}` (simulated, instant), `POST /api/account/withdrawals`

### Market data
- `GET /api/quotes?symbols=AAPL,TSLA` — from Redis
- `GET /api/symbols/{symbol}` — name, sector, last, day change
- `GET /api/symbols/{symbol}/candles?interval=1m|5m|1h|1d&from=&to=`
- `GET /api/search?q=` — symbol/name prefix search
- `GET /api/movers` — top gainers/losers (computed every few seconds, cached)

### Orders
- `POST /api/orders` (requires `Idempotency-Key`), `GET /api/orders?status=&page=`, `GET /api/orders/{id}`, `DELETE /api/orders/{id}`

### Portfolio
- `GET /api/portfolio` — cash, buying power, positions with live market value and day P/L
- `GET /api/portfolio/history?range=1D|1W|1M|1Y` — portfolio value series
- `GET /api/activity` — deposits, fills, withdrawals

### Watchlists
- `GET/POST /api/watchlists`, `PUT/DELETE /api/watchlists/{id}/symbols/{symbol}`

### WebSocket
- `/ws` — ops: `subscribe`, `unsubscribe`, `ping`; server messages: `quote`, `order`, `portfolio`, `error`

Conventions: RFC 7807 errors, cursor pagination for lists that grow (orders, activity), OpenAPI per service, `X-Request-Id` propagated end to end.

---

## 8. Scale and performance targets (local, measured with Gatling)

Run on one developer machine with services scaled via `docker compose up --scale stream-service=3 --scale order-service=2`. Numbers are targets for a reasonably powerful laptop; record actual results in `docs/load-test-results.md` either way.

| Scenario | Target |
|---|---|
| Concurrent WebSocket clients | 10,000 across stream-service instances, each subscribed to 5–20 symbols |
| Quote ticks ingested | 2,500/sec sustained; 10,000/sec for 60s burst |
| Tick-to-client latency | p99 < 250ms (conflation interval included) |
| Order placement throughput | 1,000 orders/sec sustained |
| Order API latency | p99 < 100ms for `POST /api/orders` |
| Order-to-fill latency (market orders) | p99 < 500ms end to end |
| Correctness under load | 0 lost orders, 0 duplicate fills, ledger reconciliation exact |
| Market-open burst | 5× normal order rate for 2 minutes without errors other than intended 429s |
| Hot symbol | 70% of orders on one symbol without other symbols' latency degrading > 2× |

Techniques expected (implement where they pay off, document each):
- Virtual threads for blocking I/O in order/portfolio/account services; WebFlux/Netty for stream-service.
- Kafka partitions: `quotes.ticks` 12, `orders.submitted` 12 (key = symbol), `executions` 12 (key = account_id for portfolio, re-keyed if needed), `ledger.events` 12.
- Batch Kafka produces (`linger.ms`, `batch.size`) and consumer batch processing for the ledger.
- HikariCP pool sizing based on measured concurrency, not defaults.
- Redis for hot reads (quotes, buying power snapshot); Postgres stays the source of truth.
- Read models (CQRS) for portfolio/positions so reads never contend with ledger writes.
- Gateway rate limiting (Redis token bucket) + 429 with `Retry-After`.
- Bulkheads so a slow portfolio-service can't exhaust order-service threads.

---

## 9. Phases

### Phase 1 — Core trading engine (backend)
- Docker Compose: Postgres, Kafka (KRaft), Redis, all services, Prometheus, Grafana.
- account-service (auth, simulated deposits), marketdata-service (simulator, Redis quotes, Kafka ticks, candles), order-service (validation, reservation, idempotency, outbox), execution-service (market + limit fills, cancels, DAY expiry), portfolio-service (double-entry ledger, positions, reconciliation endpoint), gateway (routing + JWT).
- Seed: 500 symbols, 2 demo users with $100k each.
- **Acceptance:**
  - `docker compose up` brings everything up healthy.
  - Deposit → buy market → position appears with correct avg cost; cash reduced by exact fill amount.
  - Limit buy below market rests, then fills when the simulated price crosses it.
  - Same `Idempotency-Key` sent 5 times concurrently → exactly one order.
  - Kill execution-service mid-stream, restart → no lost or duplicated executions; reconciliation exact.
  - Cancel-vs-fill race test (Testcontainers, 1,000 iterations) always ends in exactly one terminal state with correct balances.
  - Property-based test (jqwik): random sequences of deposits/orders/cancels never produce negative cash or unbalanced ledger txns.

### Phase 2 — Real-time streaming + web client
- stream-service with subscriptions, conflation, backpressure, Redis pub/sub for user events, multi-instance support.
- React client: login, home with portfolio value chart and positions, symbol page with live price + candle chart, buy/sell sheet (market/limit, dollars or shares), order history, watchlist, live order/fill toasts.
- **Acceptance:** two browser tabs on different stream-service instances both receive the same quotes; a fill appears on screen < 1s after the order; killing one stream-service instance causes clients to reconnect to another within 5s.

### Phase 3 — Load, scale, resilience
- Gatling simulations: `OrderFlowSimulation`, `WebSocketFanoutSimulation`, `MarketOpenBurstSimulation`, `HotSymbolSimulation`.
- Grafana dashboards: orders/sec, order latency percentiles, Kafka consumer lag per group, WebSocket connections per instance, ticks/sec, DB pool usage, JVM/GC.
- Tune to hit section 8 targets; write `docs/load-test-results.md` with before/after numbers and what changed.
- Chaos checks: kill a Kafka consumer, pause Postgres for 10s, slow down portfolio-service (Toxiproxy) — system degrades gracefully (rejects or queues) and recovers with correct balances.
- **Acceptance:** section 8 table filled in with measured results, correctness checks pass after every load run (reconciliation script returns zero diffs).

### Phase 4 — Product extras (optional)
- Price alerts (notification-service consuming ticks, per-user thresholds, delivered over WebSocket).
- Recurring buys (scheduled dollar-cost averaging).
- Debezium CDC outbox instead of polling relay.
- Historical replay mode for marketdata-service.
- Portfolio-history snapshots computed by a stream processor (Kafka Streams) instead of on-demand queries.

---

## 10. Engineering guidelines for Claude Code
- Package by feature inside each service; controllers thin; DTOs and events as Java records.
- Every event has `eventId (UUID)`, `eventType`, `occurredAt`, `version`, and a key; schemas live in `tradewind-common` and are versioned.
- Every external call (HTTP, Kafka, Redis) behind an interface; timeouts on everything — no call without a timeout.
- No `double`/`float` for money anywhere; add an ArchUnit test that fails the build if one appears in money-related packages.
- Integration tests use Testcontainers; no mocks for Kafka/Postgres in correctness tests.
- Config via `application.yml` + env vars; `.env.example` only, no secrets committed.
- Pin all Docker image tags; verify each pulls before writing it into compose.
- Each phase ends with: tests green, README updated, `docs/phase-N-notes.md` of decisions and trade-offs.

## 11. Open decisions (confirm before or during Phase 1)
- Polling outbox relay (chosen for v1, simpler) vs Debezium CDC (Phase 4).
- Sync reservation call from order-service to portfolio-service (chosen: simplest correct option) vs async saga with a pending state (more scalable, more complex — good Phase 3 experiment).
- Execution model: fill against simulated quotes like a broker routing to market makers (chosen) vs a full central limit order book matching users against each other (more interesting algorithmically, less like Robinhood).
- Service count: 7 services is realistic but heavy on a laptop; fallback is merging order + execution, and account + portfolio, if resources are tight.