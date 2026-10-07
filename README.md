# Tradewind

Robinhood-style paper-trading platform (simulated money and market data). See [spec.md](spec.md).
Phase 1 (core trading engine) is in progress.

## Prerequisites

- **JDK 21.** The build enforces it. On macOS with Homebrew:
  ```
  brew install openjdk@21
  export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
  ```
- Maven 3.9+
- Docker (8 GB of memory for Docker recommended)

## Build and test

```
mvn clean verify
```

Build output goes to `<module>/target.nosync/` instead of `target/`: the repository currently sits on an
iCloud-synced Desktop, and iCloud skips folders ending in `.nosync` (otherwise it duplicates build files mid-build).

Integration tests start their own Postgres and Redis containers with Testcontainers, so Docker must be running.

## Run locally

```
cp .env.example .env                  # then set the passwords
docker compose up -d                  # Postgres, Kafka, Redis, Prometheus, Grafana
set -a; . ./.env; set +a
mvn -pl portfolio-service spring-boot:run # :8085, ledger (source of truth for money)
mvn -pl marketdata-service spring-boot:run # :8082, price simulator + quotes/candles
mvn -pl order-service spring-boot:run      # :8083, order intake, reservation, outbox
mvn -pl execution-service spring-boot:run  # :8084, fills orders against quotes (single writer per symbol)
mvn -pl account-service spring-boot:run   # :8081
mvn -pl gateway spring-boot:run           # :8080, the only public entry point
```

Every mutating `/api` call needs an `Idempotency-Key` header (except `/api/auth/*`). Retrying with the
same key returns the original response; reusing it with a different body is a 422.

### Placing orders

```
curl -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: my-order-1' -d '{"symbol":"AAPL","side":"BUY","type":"MARKET","qty":"10"}'
# 202 ACCEPTED (buying power reserved), 422 with "code" if rejected, 503 + Retry-After if the outcome is
# unknown: retry with the same key and the same order resumes.
curl -X DELETE localhost:8080/api/orders/$ORDER_ID -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: cancel-1'
curl "localhost:8080/api/orders?limit=20" -H "Authorization: Bearer $TOKEN"     # newest first, cursor paginated
```

### Simulated market

500 symbols (42 well-known tickers plus generated ones, from `scripts/generate_symbols.py`) follow a geometric
Brownian motion with jumps, ~2,750 ticks/s in total, only while the simulated market is open (Mon-Fri
9:30-16:00 New York time). By default simulated time starts at Monday 09:29 the first time a service starts and
runs at real speed. For demos, speed it up and/or pick a start time:

```
MARKET_CLOCK_SPEED=60 MARKET_CLOCK_SIM_START=2026-01-05T09:29:00-05:00   # 1 real second = 1 market minute
docker compose exec redis redis-cli DEL market:clock-anchor              # then restart services to reset time
SIMULATOR_HOT_SYMBOL=GME                                                  # meme-stock mode
```

Ledger reconciliation (internal, not exposed through the gateway):

```
curl localhost:8085/internal/reconciliation    # "ok": true means cached balances == re-summed ledger
```

| Service | URL |
|---|---|
| Gateway (public API) | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (credentials from `.env`) |
