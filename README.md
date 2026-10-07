# Tradewind

Robinhood-style **paper-trading** platform: simulated money, simulated market data, no real broker. Built as a
high-traffic engineering exercise; see [spec.md](spec.md). This README covers **Phase 1** (core trading engine,
backend only).

## Run it

Prerequisites: Docker (8 GB of memory for Docker recommended).

```
cp .env.example .env              # then change the passwords
scripts/compose.sh up -d --build  # first run builds the images (a few minutes)
docker compose ps                 # wait until all 11 services are "healthy"
```

`scripts/compose.sh` is plain `docker compose` run through an ASCII-named symlink: Docker's builder rejects the
"—" in this folder's name. Commands that do not build (`docker compose up -d`, `ps`, `logs`, `down`) work directly.
Stop with `docker compose down` (add `-v` to wipe all data).

Two demo users are seeded with $100,000 each: `demo1@tradewind.test` and `demo2@tradewind.test`, password
`DEMO_USER_PASSWORD` from `.env`.

| Service | URL |
|---|---|
| Gateway (the public API) | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin credentials from `.env`) |
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

Outside market hours only GTC limit orders are accepted; DAY orders expire at 16:00.

## Architecture (Phase 1)

| Module | Role |
|---|---|
| `gateway` | Routing, JWT validation (RS256 via account-service JWKS), identity headers, per-user order rate limit |
| `account-service` | Register/login/refresh/logout, simulated deposits and withdrawals, demo-user seed |
| `marketdata-service` | Price simulator, Redis quotes, `quotes.ticks`, 1-minute candles, quote/symbol/search API |
| `order-service` | Validation, synchronous buying-power reservation (timeout, bulkhead, circuit breaker), idempotency, order state machine, outbox |
| `execution-service` | Fills market/limit orders against quotes; single writer per symbol; cancels; DAY expiry |
| `portfolio-service` | Double-entry ledger (source of truth for money), reservations, fills, positions, reconciliation |
| `tradewind-common` | Event schemas, money types, outbox, idempotency, Kafka helpers, ArchUnit money rule |

Kafka topics (12 partitions each): `quotes.ticks` and `orders.submitted` keyed by symbol, `executions` and
`ledger.events` keyed by account. Every state change is published through a transactional outbox; every consumer
is idempotent. Design decisions, trade-offs and how each acceptance criterion was verified: [docs/phase-1-notes.md](docs/phase-1-notes.md).

## Develop

- **JDK 21** (enforced): `export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`
- Maven 3.9+. `mvn clean verify` runs unit tests, Testcontainers integration tests and the cross-service
  `correctness-tests` suite (real service jars as processes: idempotency, 1,000-iteration cancel-vs-fill race,
  kill -9 recovery). About 5 minutes; Docker must be running.
- Build output goes to `<module>/target.nosync/` instead of `target/`: this checkout sits on an iCloud-synced
  Desktop, and iCloud skips `*.nosync` folders (otherwise it duplicates build files mid-build).
- Run a service from the IDE or `mvn -pl <module> spring-boot:run` against the compose infrastructure
  (`docker compose up -d postgres kafka redis`); `.env.example` lists every setting with its default.
