# Load tests (Phase 3)

Gatling simulations (Java DSL) and a correctness checker, run on the host against the **running** compose stack.
`mvn verify` compiles this module but never runs it.

```
scripts/compose.sh up -d --build --scale stream-service=3 --scale order-service=2
scripts/load.sh setup                        # once: 1,000 funded load users (load-0001@tradewind.test ...)
scripts/load.sh OrderFlowSimulation          # smoke: 20 users x 1 order/s for 30 s, then the correctness check
scripts/load.sh check                        # correctness check only
```

`scripts/load.sh <Simulation>` exits non-zero if the simulation's assertions fail **or** the correctness check
fails. Reports: `load-tests/target.nosync/gatling/<simulation>-<timestamp>/index.html`, console output next to them.

## Settings

System properties `-Dload.<key>=<value>` (or environment variables, e.g. `LOAD_SIM_USERS`):

| Key | Default | Meaning |
|---|---|---|
| `users` | 1000 | load users to create/fund (`setup`) |
| `simUsers` | 20 | virtual users in a simulation (each is one load user, logged in once) |
| `perUserOrdersPerSec` | 1.0 | orders per second per virtual user (gateway limit: 10/s, burst 20) |
| `duration` / `rampUp` | 30s / 10s | steady phase / ramp |
| `gatewayUrl` | http://localhost:8080 | load goes through the public API |
| `portfolioUrl`, `kafkaBootstrapServers`, `postgresHost` | localhost | for the correctness check |

Postgres credentials come from the repository's `.env`.

## Correctness check

After every run (spec 8 "correctness under load"), once the system is quiet (outboxes drained, no consumer lag on
order/execution topics, no PENDING orders):

- **0 lost orders:** every order past PENDING is known to execution-service (resting or closed) with the same
  outcome; rejected orders never reached it.
- **0 duplicate fills:** executions map one-to-one onto portfolio FILL ledger transactions; each order's filled
  quantity and last execution sequence agree across order-service and execution-service.
- **No stranded reservations:** no open reservation for a finished order.
- **Ledger:** portfolio-service reconciliation is `ok` (balanced, cached balances match, nothing negative).
