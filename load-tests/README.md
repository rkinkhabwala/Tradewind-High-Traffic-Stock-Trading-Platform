# Load tests (Phase 3)

Gatling simulations (Java DSL) and a correctness checker, run on the host against the **running** compose stack.
`mvn verify` compiles this module but never runs it.

```
scripts/compose.sh up -d --build --scale stream-service=3 --scale order-service=2
scripts/load.sh setup                        # once: 1,000 funded load users (load-0001@tradewind.test ...)
scripts/load.sh OrderFlowSimulation          # smoke: 20 orders/s for 30 s, then the correctness check
scripts/load.sh check                        # correctness check only
```

## Simulations (spec section 8)

| Simulation | Measures | Main settings |
|---|---|---|
| `OrderFlowSimulation` | Order throughput, API latency, order-to-fill | `ordersPerSec` (20), `rampUp` (30s), `duration` (30s) |
| `MarketOpenBurstSimulation` | Normal rate, 5x for 2 min, normal; only 202/429 allowed | `ordersPerSec` (20), `burstFactor` (5), `normal` (60s), `burst` (120s) |
| `HotSymbolSimulation` | 70% of orders on one symbol; "hot" vs "other" latency | `ordersPerSec` (20), `hotSymbol` (AAPL), `hotShare` (70) |
| `WebSocketFanoutSimulation` | Concurrent clients, tick-to-client, optional tick burst | `clients` (100), `rampUp`, `hold`, `minSymbols`/`maxSymbols` (5/20), `tickBurstMultiplier`, `tickBurstSeconds` |

Order simulations use an open model: one market buy (1 share) per virtual user from a random, already logged-in
load user, so the arrival rate is exact whatever the latency. They also open one WebSocket per load user
(`FillObserver`) and time order-to-fill as the user sees it: from just before the request to the pushed FILLED
event. The fan-out simulation records tick-to-client from each quote's `publishedAt`. The tick burst uses
marketdata-service's internal `POST /internal/simulator/rate {"multiplier":3.6,"durationSeconds":60}`, which
reverts on its own.

Each run writes `load-tests/target.nosync/results/<Simulation>-<time>.json`: client-side histograms
(HdrHistogram), counters by HTTP status, and the server view from Prometheus for the same window (gateway and
order-service latency, order-to-fill, tick-to-client, ticks/s, consumer lag, DB pool waiters, CPU). Results and
analysis: [docs/load-test-results.md](../docs/load-test-results.md).

`scripts/load.sh <Simulation>` exits non-zero if the simulation's assertions fail **or** the correctness check
fails. Reports: `load-tests/target.nosync/gatling/<simulation>-<timestamp>/index.html`, console output next to them.

Restarting services leaves the JVMs cold: the first minute of load is much slower, and slow reservation calls can
open order-service's circuit breaker (orders then fail fast as RESERVATION_TIMEOUT, by design). Warm up with a short
run before measuring.

## Settings

System properties `-Dload.<key>=<value>` (or environment variables, e.g. `LOAD_SIM_USERS`):

| Key | Default | Meaning |
|---|---|---|
| `users` | 1000 | load users to create/fund (`setup`) |
| `observeFills` | true | open the 1,000 user sockets that time order-to-fill |
| `gatewayUrl` | http://localhost:8080 | load goes through the public API |
| `portfolioUrl`, `kafkaBootstrapServers`, `postgresHost`, `prometheusUrl`, `marketdataUrl` | localhost | correctness check, run reports, tick burst |

Postgres credentials come from the repository's `.env`.

## Correctness check

After every run (spec 8 "correctness under load"), once the system is quiet (outboxes drained, no consumer lag on
order/execution topics, no PENDING orders, no reservation release still being retried; waits up to 10 minutes):

- **0 lost orders:** every order past PENDING is known to execution-service (resting or closed) with the same
  outcome; rejected orders never reached it.
- **0 duplicate fills:** executions map one-to-one onto portfolio FILL ledger transactions; each order's filled
  quantity and last execution sequence agree across order-service and execution-service.
- **No stranded reservations:** no open reservation for a finished order.
- **Ledger:** portfolio-service reconciliation is `ok` (balanced, cached balances match, nothing negative).
