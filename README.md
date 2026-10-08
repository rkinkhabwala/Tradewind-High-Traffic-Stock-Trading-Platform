# Tradewind

Robinhood-style **paper-trading** platform: simulated money, simulated market data, no real broker. Built as a
high-traffic engineering exercise; see [spec.md](spec.md). This README covers **Phase 1** (core trading engine)
and **Phase 2** (real-time streaming and the web app).

## Run it

Prerequisites: Docker (8 GB of memory for Docker recommended).

```
cp .env.example .env                                         # then change the passwords
scripts/compose.sh up -d --build --scale stream-service=3    # first run builds the images (a few minutes)
docker compose ps                                            # wait until all 14 containers are "healthy"
```

Then open **http://localhost:3001** and log in as a demo user (below). Without `--scale` one stream-service instance
runs; the app works the same, and the gateway spreads WebSocket clients over however many instances exist.

`scripts/compose.sh` is plain `docker compose` run through an ASCII-named symlink: Docker's builder rejects the
"—" in this folder's name. Commands that do not build (`docker compose up -d`, `ps`, `logs`, `down`) work directly.
Stop with `docker compose down` (add `-v` to wipe all data).

Two demo users are seeded with $100,000 each: `demo1@tradewind.test` and `demo2@tradewind.test`, password
`DEMO_USER_PASSWORD` from `.env`.

| Service | URL |
|---|---|
| Web app (nginx: the React app, `/api` and `/ws` proxied to the gateway) | http://localhost:3001 |
| Gateway (the public API) | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin credentials from `.env`); dashboards in the **Tradewind** folder: Trading, Streaming, Platform |
| Reconciliation (internal) | http://localhost:8085/internal/reconciliation |

## Try it

```
G=localhost:8080
TOKEN=$(curl -s -X POST $G/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"demo1@tradewind.test","password":"change-me-demo"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

curl -s -H "$AUTH" "$G/api/quotes?symbols=AAPL,TSLA"
curl -s -H "$AUTH" "$G/api/symbols/AAPL"
curl -s -H "$AUTH" "$G/api/symbols/AAPL/candles?interval=5m"
curl -s -H "$AUTH" "$G/api/search?q=app"

# Every mutating call needs an Idempotency-Key; retrying with the same key returns the original response.
curl -s -X POST $G/api/orders -H "$AUTH" -H 'Content-Type: application/json' -H 'Idempotency-Key: buy-1' \
  -d '{"symbol":"AAPL","side":"BUY","type":"MARKET","qty":"10"}'
curl -s -X POST $G/api/orders -H "$AUTH" -H 'Content-Type: application/json' -H 'Idempotency-Key: buy-2' \
  -d '{"symbol":"TSLA","side":"BUY","type":"LIMIT","qty":"2","limitPrice":"200.00","timeInForce":"GTC"}'
curl -s -H "$AUTH" "$G/api/orders?limit=20"
curl -s -X DELETE -H "$AUTH" -H 'Idempotency-Key: cancel-1' $G/api/orders/<orderId>

curl -s -H "$AUTH" $G/api/portfolio        # cash, buying power, positions with live value and P/L
curl -s -H "$AUTH" $G/api/activity         # deposits, withdrawals, fills (cursor-paginated)
curl -s -X POST $G/api/account/deposits -H "$AUTH" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: dep-1' -d '{"amountCents":500000}'

curl -s localhost:8085/internal/reconciliation   # "ok": true = ledger and cached balances agree exactly
```

New users: `POST /api/auth/register {"email","password"}`, then log in and deposit.

Login and refresh return only the access token; the refresh token is an httpOnly, `SameSite=Strict` cookie
(`tw_refresh`, path `/api/auth`), so command-line clients need a cookie jar (`curl -c jar` / `-b jar`) to refresh.

### Real-time stream

```
TICKET=$(curl -s -X POST -H "$AUTH" $G/api/ws/ticket | jq -r .ticket)   # one-time, valid 30 s
websocat "ws://localhost:8080/ws?ticket=$TICKET"
{"op":"subscribe","symbols":["AAPL","TSLA"]}
{"op":"ping"}
```

The server sends `hello` (with the serving instance's id), `subscriptions`, `quote` (the stored quote at once on
subscribe, then at most one per symbol per 250 ms), `order` and `portfolio` (the user's own changes, pushed after
they commit), `pong` and `error`. Slow clients are closed with code 4000, silent ones (no message for 45 s) with
4001; clients reconnect with backoff and a new ticket.

### Simulated market

500 symbols (42 well-known tickers plus generated ones, from `scripts/generate_symbols.py`) follow a geometric
Brownian motion with jumps, ~2,750 ticks/s in total, only while the simulated market is open (Mon-Fri 9:30-16:00
New York time). Simulated time starts at Monday 09:29 the first time the stack starts and runs at real speed, so
the market opens a minute later and stays open for 6.5 hours. To change it, set in `.env` and restart:

```
MARKET_CLOCK_SPEED=60                                  # 1 real second = 1 market minute (a session lasts 6.5 min)
MARKET_CLOCK_SIM_START=2026-01-05T09:29:00-05:00
SIMULATOR_HOT_SYMBOL=GME                               # meme-stock mode: 100 ticks/s, 5x volatility
docker compose exec redis redis-cli DEL market:clock-anchor   # then restart the services to reset market time
```

Outside market hours only GTC limit orders are accepted; DAY orders expire at 16:00. When the simulated session has
ended, `scripts/next-market-open.sh` moves the shared clock to 09:29 of the next trading day and restarts the four
services that read it (later than any recorded data, so it behaves like a normal next day).

## Architecture

| Module | Role |
|---|---|
| `gateway` | Routing, JWT validation (RS256 via account-service JWKS), identity headers, per-user order rate limit |
| `account-service` | Register/login/refresh/logout, simulated deposits and withdrawals, demo-user seed |
| `marketdata-service` | Price simulator, Redis quotes, `quotes.ticks`, 1-minute candles, quote/symbol/search API |
| `order-service` | Validation, synchronous buying-power reservation (timeout, bulkhead, circuit breaker), idempotency, order state machine, outbox |
| `execution-service` | Fills market/limit orders against quotes; single writer per symbol; cancels; DAY expiry |
| `portfolio-service` | Double-entry ledger (source of truth for money), reservations, fills, positions, reconciliation, portfolio value snapshots and history |
| `stream-service` | WebSocket fan-out: every instance reads all of `quotes.ticks`, conflates per session, relays user events from Redis pub/sub; scaled horizontally |
| `web/` | React + TypeScript app served by nginx on :3001 (not a Maven module) |
| `tradewind-common` | Event schemas, money types, outbox, idempotency, Kafka helpers, ArchUnit money rule |

Kafka topics (12 partitions each): `quotes.ticks` and `orders.submitted` keyed by symbol, `executions` and
`ledger.events` keyed by account. Every state change is published through a transactional outbox; every consumer
is idempotent. order-service and portfolio-service also push each committed change to Redis channel
`user:{accountId}`; the stream-service instance(s) holding that account's sockets relay it. The gateway resolves
`stream-service` through Docker's DNS every 2 s, health-checks each instance and spreads WebSocket connections
round-robin.

Design decisions, trade-offs and how each acceptance criterion was verified:
[docs/phase-1-notes.md](docs/phase-1-notes.md), [docs/phase-2-notes.md](docs/phase-2-notes.md).

## Develop

- **JDK 21** (enforced): `export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`
- Maven 3.9+. `mvn clean verify` runs unit tests, Testcontainers integration tests and the cross-service
  `correctness-tests` suite (real service jars as processes: idempotency, 1,000-iteration cancel-vs-fill race,
  kill -9 recovery). About 5 minutes; Docker must be running.
- Build output goes to `<module>/target.nosync/` instead of `target/`: this checkout sits on an iCloud-synced
  Desktop, and iCloud skips `*.nosync` folders (otherwise it duplicates build files mid-build).
- Run a service from the IDE or `mvn -pl <module> spring-boot:run` against the compose infrastructure
  (`docker compose up -d postgres kafka redis`); `.env.example` lists every setting with its default.

### Web app (`web/`)

Node 24+. Exact dependency versions are pinned in `package.json`.

```
cd web
npm ci
npm test            # Vitest: API client, stream client, money math, pages (jsdom)
npm run dev         # http://localhost:5173, proxies /api and /ws to the gateway on :8080
npm run build       # type-check + production build (to dist.nosync/)
```

`node_modules` is excluded from iCloud with the `com.apple.fileprovider.ignore#P` attribute
(`xattr -w 'com.apple.fileprovider.ignore#P' 1 web/node_modules` after a fresh clone); build output goes to
`*.nosync` folders.

### Phase 2 acceptance tests (Playwright)

Run against the running stack with three stream-service instances; they register and fund their own user, kill
one stream-service instance and bring it back afterwards:

```
scripts/compose.sh up -d --build --scale stream-service=3
cd web && npx playwright install chromium && npm run e2e
```

The suite checks that two tabs on different instances receive the same quotes, that a market order's fill shows on
screen within 1 s (20 orders; p50/p95/max are printed and the test passes only if p95 < 1 s), and that clients of a
killed instance reconnect elsewhere within 5 s (the measured time is printed). Results also go to
`web/test-results.nosync/phase2-acceptance.json`. If the simulated market is closed the setup says so; run
`scripts/next-market-open.sh` first.

### Load tests (Phase 3)

Gatling simulations and the post-run correctness checker live in `load-tests/`; see
[load-tests/README.md](load-tests/README.md). `scripts/load.sh setup` once, then
`scripts/load.sh <SimulationName>`.

Grafana dashboards (provisioned from `infra/grafana/provisioning/dashboards/json`, generated by
`infra/grafana/dashboards.py`; edit the script, not the JSON):

- **Trading:** orders/sec by status, `POST /api/orders` latency p50/p95/p99 at the gateway and inside order-service,
  order-to-fill, reservation-call latency, fills/sec, ledger lock conflicts, circuit breaker and bulkhead, 5xx.
- **Streaming:** WebSocket connections per instance, ticks/sec (produced and received per instance), tick-to-client
  latency (from the tick's wall-clock `publishedAt` to the socket write), Kafka delivery lag, conflation/drops.
- **Platform:** Kafka consumer lag per group and topic (kafka-exporter), DB pool usage/waiters/acquire time, JVM heap,
  GC, CPU, threads.

Every metric carries an `application` tag; HTTP and the new latency timers publish histograms, so percentiles are
computed in Prometheus across instances.
