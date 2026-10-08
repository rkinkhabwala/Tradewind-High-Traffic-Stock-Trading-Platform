# Load test results

Spec section 8 targets, measured with the Gatling simulations in [`load-tests/`](../load-tests/README.md) against
the compose stack on one laptop. The **baseline** (milestone u) is the system as built in Phases 1–2, before any
tuning. Results after tuning (milestone v) and after the chaos checks (milestone w) will be added below.

## Setup

| | |
|---|---|
| Machine | Apple M1 Pro, 10 cores, 16 GB |
| Docker Desktop | 10 CPUs, 7.75 GiB; every Java service capped at 448 MiB (stream-service 384 MiB), heap 70% of that |
| Stack | `scripts/compose.sh up -d --build --scale stream-service=3 --scale order-service=2` (spec section 8): 17 containers |
| Load generator | Gatling 3.16 on the host JVM (3 GB heap), sharing the same CPUs as Docker |
| Users | 1,000 funded load users (`scripts/load.sh setup`), logged in before each run |
| Orders | Market buys of 1 share of 42 liquid symbols; one order per Gatling virtual user (open model, exact arrival rate) |

**How things are measured.**

- **Order API latency:** measured by the client, from just before the request is sent to the response.
- **Order-to-fill:** measured by the client, from just before the order request to the moment the FILLED event
  arrives on that user's own WebSocket. This is the end-to-end path the web app shows; 1,000 observer sockets are
  open, one per load user.
- **Tick-to-client:** Gatling's receive timestamp minus the quote's `publishedAt` (wall clock when marketdata
  published the tick). Host and Docker clocks were within a few ms; the server-side `stream.tick.to.client`
  histogram is the cross-check.
- **Server-side numbers** come from Prometheus over the same window, so they match the Grafana dashboards.
- **Correctness check** (`scripts/load.sh check`) runs after every run: lost orders, duplicate fills, filled
  quantity and outcome agreement across services, stranded reservations, ledger reconciliation.

## Section 8 targets: baseline

| Scenario | Target | Baseline (untuned) |
|---|---|---|
| Concurrent WebSocket clients | 10,000 across instances, 5–20 symbols each | **3,525 maximum**; the gateway restarted twice under the load and dropped every proxied socket |
| Quote ticks ingested | 2,500/s sustained; 10,000/s for 60 s | **Met**: ~2,750/s sustained; **9,894/s** over a 60 s burst (3.6x boost) |
| Tick-to-client latency | p99 < 250 ms (conflation included) | **Not met**: p99 310 ms server-side at 3.5k clients (client-side 335–398 ms at 300 clients). During the burst p99 223 ms (fresher quotes win the 250 ms conflation window) |
| Order placement throughput | 1,000 orders/s sustained | **Not met**: about **65–80 accepted orders/s** is the most the system sustains; offered 1,000/s, only ~22/s were accepted |
| Order API latency | p99 < 100 ms | **Not met**: client-side p99 4.0 s already at 50 orders/s; 59 s at 1,000/s |
| Order-to-fill (market) | p99 < 500 ms end to end | **Not met**: p99 700 ms at 100 orders/s, 10 s at 50/s in a bad run, 60 s at 1,000/s |
| Correctness under load | 0 lost orders, 0 duplicate fills, reconciliation exact | **Met in every run**: 0 lost, 0 duplicate fills, reconciliation ok. Two problems were found on the way (see below) |
| Market-open burst | 5x normal for 2 min, only intended 429s | **Not met**: at 1,000→5,000 orders/s the system and the load generator saturated: 0 intended 429s, 18k 5xx, 721k requests without a response (client file descriptors and ports exhausted while the server stalled) |
| Hot symbol | 70% on one symbol, others' latency < 2x | **Not measurable at the target rate**: everything failed at 1,000/s (~14 accepted/s). At 50/s "other" vs "hot" p99 were similar (1.0 vs 0.9 s API, 1.2 vs 1.0 s order-to-fill) |

## Baseline runs

| Run | Offered | Accepted (server) | API p50 / p99 (client, 202s) | Order-to-fill p50 / p99 (client) | Errors | Correctness |
|---|---|---|---|---|---|---|
| OrderFlow | 50/s, 45 s | 42/s | 22 ms / 4.0 s | 108 ms / 10.1 s | 260 × 503, 25 × 504 | OK |
| OrderFlow | 100/s, 60 s | 66/s | 18 ms / 4.4 s | 97 ms / 700 ms | 662 × 503, 842 no response | OK |
| OrderFlow | 200/s, 60 s | 80/s | 101 ms / 9.9 s | 1.5 s / 19.6 s | 8,264 no response, 273 × 5xx | OK |
| OrderFlow | 1,000/s, 3 min | 22/s | 295 ms / 58.7 s | 3.2 s / 59.5 s | 188k no response, 17k × 5xx | OK (see release backlog) |
| MarketOpenBurst | 1,000 → 5,000 → 1,000/s | 12/s | 10.3 s / 63 s | 3.5 s / 93.5 s | 721k no response, 18k × 5xx | OK |
| HotSymbol | 50/s, 70% AAPL | 45/s | hot 15 ms / 882 ms, other 15 ms / 1.0 s | hot 91 ms / 1.0 s, other 92 ms / 1.2 s | none | OK |
| HotSymbol | 1,000/s, 70% AAPL | 14/s | 224 ms / 9.4 s | 1.3 s / 15.3 s | 208k no response, 428 × 5xx | OK |
| WebSocketFanout | 300 clients, burst 3.6x 20 s | n/a | n/a | tick-to-client p50 67 ms / p99 335 ms (burst p99 213 ms) | none | OK |
| WebSocketFanout | 10,000 clients, burst 3.6x 60 s | 3,525 max connected | n/a | tick-to-client server p99 310 ms (burst 223 ms) | 62% of WebSocket actions failed after the gateway restarts | OK |

Run reports with every number: `load-tests/target.nosync/results/*.json` (not committed; regenerated by each run).

## What the baseline shows (input for tuning)

1. **The gateway is the first wall.** Most failures never got a response. During every overload the gateway logged
   `Redis command timed out` (500 ms) from the order rate limiter and the WebSocket-ticket store, and `No servers
   available for service: order-service`: its 1 s health probe failed for **both** order-service instances at
   once, so it removed them and answered 503. Under the 10k WebSocket load the gateway (448 MiB) died twice without
   a JVM error message. That is consistent with the container being killed for its off-heap (Netty) memory, but
   not confirmed.
2. **order-service waits for database connections and for portfolio-service.** Up to 388 threads were waiting for
   a pool of 20 connections. Reservation calls took up to 6.3 s (p99), so the circuit breaker opened and the
   bulkhead filled; most 503s are those fast failures.
3. **Instability even at low rates.** Runs at 20–50 orders/s were sometimes clean and sometimes had 503/504s and
   multi-second p99s, which points to the systemic issues above rather than raw capacity.
4. **Memory headroom is thin everywhere.** Every JVM container sat at 90–99% of its limit before any load.
5. **Tick fan-out is close but not there:** p99 ~310 ms with 3.5k clients. With 250 ms conflation a quote can
   wait up to 250 ms for its window by design, so the target leaves ~0 ms for everything else at p99.
6. **The load generator can saturate too.** With the server stalling, 5,000 requests/s with long timeouts
   exhausted the client's file descriptors and ephemeral ports. Gatling's request timeout is now 10 s (the gateway
   gives up after 5 s) and its JVM may open more files (`-XX:-MaxFDLimit`).

## Problems found while measuring

- **Consumers stopped for good after a rebalance (fixed).** Under load a batch took longer than
  `max.poll.interval.ms`, Kafka removed the consumer from its group, the next commit failed, and
  `EventConsumerLoop` (order-service, portfolio-service) and `ExecutionConsumer` (execution-service) exited
  permanently while the service still reported healthy. order-service then stopped applying fills (2,129 records of
  lag, no consumers). Both loops now recreate the consumer and re-join. Redelivered records are absorbed by the
  idempotent handlers: after the restart the backlog was applied and the correctness check passed with no status
  disagreements. Regression test: `EventConsumerLoopTest` (Testcontainers).
- **Slow but correct cleanup under overload.** Orders rejected after a reservation timeout are released by a
  background sweeper. After the 1,000/s run this took ~3 minutes (portfolio-service still recovering), during which
  54 reservations were briefly open for already-rejected orders. All were released, nothing was lost. The checker
  now treats "release still pending" as not yet quiet, instead of reporting it as stranded money.

## Reproduce

```
scripts/compose.sh up -d --build --scale stream-service=3 --scale order-service=2
scripts/next-market-open.sh                     # if the simulated market is closed
scripts/load.sh setup
scripts/load.sh OrderFlowSimulation -Dload.ordersPerSec=1000 -Dload.rampUp=60s -Dload.duration=180s
scripts/load.sh MarketOpenBurstSimulation -Dload.ordersPerSec=1000 -Dload.normal=60s -Dload.burst=120s
scripts/load.sh HotSymbolSimulation -Dload.ordersPerSec=1000 -Dload.rampUp=60s -Dload.duration=180s
scripts/load.sh WebSocketFanoutSimulation -Dload.clients=10000 -Dload.rampUp=120s -Dload.hold=180s \
    -Dload.tickBurstMultiplier=3.6 -Dload.tickBurstSeconds=60
```
