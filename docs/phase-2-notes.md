# Phase 2 notes: real-time streaming and the web app

Decisions, trade-offs, deviations from [spec.md](../spec.md) and how each Phase 2 acceptance criterion was verified.
For running the system see the [README](../README.md); Phase 1 is in [phase-1-notes.md](phase-1-notes.md).

## Acceptance criteria (spec §9, Phase 2)

Verified on 2026-10-08 by the Playwright suite (`web/e2e/phase2-acceptance.spec.ts`) in Chromium, against the
compose stack started with `--scale stream-service=3`, through nginx on :3001 like a real user. Three consecutive
runs, all passing:

| Criterion | How it is measured | Run 1 | Run 2 | Run 3 |
|---|---|---|---|---|
| Two tabs on different stream-service instances both receive the same quotes | Two tabs of one browser, re-opened until the gateway places them on different instances (shown by the `hello` instance id). Both open AAPL; every quote frame on both sockets is recorded for 5 s and compared by (symbol, market time, price); both screens must also converge on the same price | 11 / 11 quotes identical | 11 / 11 | 11 / 11 |
| A fill appears on screen < 1 s after the order | 20 market buys through the UI, one after another. Timed in the page: from the click on Submit to the "Bought …" toast entering the DOM (MutationObserver, so no test-harness latency is counted). Pass only if p95 < 1 s | p50 108 ms, p95 144 ms, max 155 ms | p50 108, p95 140, max 179 | p50 120, p95 154, max 158 |
| Killing a stream-service instance makes clients reconnect to another within 5 s | `docker kill` of the instance serving the page; time from issuing the kill to the page being live on a different instance (in-page log of the connection badge's state) | 102 ms | 348 ms | 119 ms |

`docker kill` itself took 230–400 ms to return, so in two runs the page was back on another instance before the
command finished. With 30 clients and no browser (`LiveFailover`, milestone n) the 10 affected clients reconnected
with a median of 236–320 ms and a maximum of 2.3–2.4 s: with three instances, a client whose first retry lands on
the dead instance in the ≤ 2 s before the gateway's next health check backs off once more. Clients on the other
instances were never disturbed.

Automated tests at the end of Phase 2: `mvn clean verify` 234 tests (0 skipped), Vitest 64, Playwright 3.

## Approved decisions and how they were implemented

| # | Decision | Implementation |
|---|---|---|
| 1 | No JWT in the WebSocket URL | `POST /api/ws/ticket` (authenticated) returns a random ticket stored in Redis for 30 s. `/ws?ticket=` is redeemed with `GETDEL` (single use), the ticket is removed from the forwarded URL and `X-User-Id`/`X-Account-Id` are injected as for REST. The client fetches a new ticket for every connection. Neither nginx nor the gateway logs query strings |
| 2 | Gateway DNS discovery, round-robin, health checks; hello with instance id | `StreamInstances` resolves `stream-service` every 2 s (JVM DNS cache shortened to 1 s), probes `/actuator/health` (1 s timeout) and feeds the healthy set to Spring Cloud LoadBalancer's round-robin for `lb://stream-service`. stream-service is DOWN until it consumes ticks. `hello.instanceId` is the container's hostname |
| 3 | Direct partition assignment from latest; current quote on subscribe | Every instance assigns all 12 partitions of `quotes.ticks` (no consumer group, nothing to commit) and seeks to the end; on subscribe the stored Redis quote (with `prevClose`) is sent at once, skipped if a live tick already arrived |
| 4 | App-level ping/pong every 15 s, close after 45 s of silence, 4000 for slow clients | Client pings every `heartbeatSeconds` from hello; the server closes a session silent for 45 s with **4001** and a client that cannot keep up for 10 s with **4000**; the client also replaces a socket it has heard nothing from for 45 s |
| 5 | `user:{accountId}` channel, best effort, published after commit | `UserEventPublisher` (common) serializes inside the transaction and publishes in `afterCommit` from a single background thread with a bounded queue (drops counted). stream-service subscribes to an account's channel only while it holds one of that account's sockets. The client refetches all REST state after every reconnect |
| 6 | Portfolio history from snapshots | See the trade-off below |
| 7 | Dollar orders converted client-side | Dollars → shares at the ask (buy), bid (sell) or limit price, rounded **down** to 6 decimals with exact BigInt arithmetic, shown as "≈ … (estimate)". No backend money-path changes |
| 8 | Watchlists in account-service with Idempotency-Key | `/api/watchlists` CRUD; every mutation goes through the Phase 1 idempotent executor |
| 9 | Access token in memory, refresh token in an httpOnly cookie | `tw_refresh`: httpOnly, Secure, SameSite=Strict, path `/api/auth`, 7 days, rotated on every refresh. Nothing in localStorage/sessionStorage (asserted by a test). See the cross-tab refresh lock below |
| 10 | nginx on :3001, Vite proxy in dev | `nginx:1.30.5-alpine3.24` (pinned, pulled first) serves the app with a strict CSP and proxies `/api` and `/ws` to the gateway; the Vite dev server proxies the same paths |
| 11 | Plain CSS modules + design tokens | `web/src/styles/tokens.css` (light and dark), one CSS module per component |
| 12 | Playwright E2E against the running stack | `web/e2e`, reports p50/p95/max and the reconnect time, see above |

## Trade-off: portfolio history from snapshots

`GET /api/portfolio/history?range=1D|1W|1M|1Y` reads `portfolio_snapshots(account_id, ts, value_cents)`, written by
a scheduled job in portfolio-service every 5 simulated minutes while the market is open (plus a 16:00 closing
snapshot) for every account with a non-zero balance, in keyset batches of 500 with one pipelined quote read per
batch. 1D returns the raw 5-minute points of the latest session; 1W/1M/1Y are downsampled in SQL (`date_bin`, last
value per 30 min / 2 h / 1 day), so even 1Y is ~250 points. The client appends the live value as the last point.

- **Gained:** history reads are one indexed range scan, independent of how many fills or ticks an account has; the
  cost is paid once per 5 minutes in the background rather than on every request under heavy traffic.
- **Given up:** resolution is 5 minutes (the live point keeps the end current); history starts when the job first
  sees an account (no back-fill from fills and candles); an account holding a symbol with no quote is skipped for
  that bucket rather than recorded at a wrong value; snapshot timestamps are simulated market time.
- **Cost:** one row per active account per 5 market minutes (~78 per account per session). Fine for Phase 2; long
  ranges would want retention or rollups.
- **Phase 4** replaces the job with Kafka Streams (value updated from fills and ticks as they happen); the table
  and endpoint can stay, so this is a stepping stone rather than a conflict.

## Phase 4 candidate: `notionalCents` orders

Dollar orders are converted to a share quantity in the browser (decision 7), so the server only ever sees
quantities and the money path is unchanged. A server-side `notionalCents` field on `POST /api/orders` would let
execution fill "exactly $100 worth" and reserve exactly that amount; it touches validation, reservation sizing,
execution and the fill rounding rules, so it is recorded here as a Phase 4 candidate rather than done now.

## Other decisions and deviations

| Topic | Choice | Why |
|---|---|---|
| Server message types | Spec lists `quote`, `order`, `portfolio`, `error`; added `hello` (approved), `subscriptions` (ack with the full list after each change) and `pong` | Clients need to know the instance, the effective subscriptions, and that heartbeats are answered |
| Idle close code | 4001 (slow is 4000) | Lets the client and the logs tell the two apart |
| `prevClose` | Only on the subscribe-time quote | Ticks on Kafka do not carry it; the client keeps it |
| Subscription limit | 100 symbols per connection (`STREAM_MAX_SUBSCRIPTIONS`) | Spec's load profile is 5–20; bounds per-session memory |
| Backpressure | Pending quotes keyed by symbol (newer replaces older), emitted only on transport demand; other messages in a bounded queue that is never dropped (overflow closes 4000) | Memory per session is bounded by its subscriptions; a stalled client cannot grow buffers |
| Order events | Every status change except PENDING, as the same `OrderView` as `GET /api/orders/{id}`, hooked into the only two status writers (`UPDATE … RETURNING *`) | No status change can skip the push |
| Portfolio events | Full balance snapshot (cash, reserved, buying power, position qty) next to the `PortfolioUpdated` outbox write | Clients apply the latest one; no ordering assumptions |
| Watchlist limits | 20 lists per account, 100 symbols per list | Spec is silent; bounds storage per user |
| Bulk candles endpoint | Dropped from the plan | Snapshots replaced on-demand history |
| Cross-tab refresh | **Found by the E2E suite and fixed:** refreshes are serialized across tabs with the Web Locks API | Two tabs loading at once refreshed concurrently with the same rotating cookie and one was logged out; worse, a stale cookie sent later counts as theft and revokes every session of the user. Under the lock a tab refreshes only after the previous tab stored the new cookie |
| Live valuation | Recomputed in the browser from cash, reserved cash and each position (exact BigInt decimals, half-even per position like the server) | Updates on every tick without polling REST |
| Charts | lightweight-charts gets `cents / 100` as a float | Drawing only; every money calculation uses integer cents or exact decimals. Axes show New York market time |
| Times on screen | Charts and candles: simulated market time. Orders "Placed": wall-clock | Same split as Phase 1 (order/activity timestamps are wall-clock) |
| Market clock | `scripts/next-market-open.sh` moves the shared anchor to the next trading day's 09:29 and restarts the clock-reading services | The simulated session closes after 6.5 real hours; resetting to the same day would overlap recorded candles and snapshots |
| Default scale | `docker compose up` runs 1 stream-service; `--scale stream-service=3` for the acceptance setup | As in spec §8 |
| JS bundle | 532 KB (169 KB gzip), mostly the chart library | Under the budget that matters here; can be split by lazy-loading the chart |
| iCloud | `web/node_modules` excluded with the `com.apple.fileprovider.ignore#P` attribute; build and test output in `*.nosync` | Same reason as `target.nosync` in Phase 1 (npm replaces a symlinked `node_modules`) |

## Known limitations

- **Instances are not removed on a failed connection, only by health checks.** An instance that dies keeps receiving
  new connections until the next check (≤ 2 s). Clients retry with backoff, so this costs at most a few seconds;
  marking an instance down on the first failed connection would tighten the tail.
- **Redis restarts are untested for user-event channels.** stream-service resubscribes with backoff when a
  subscription fails, but recovery after a Redis restart has not been tested. REST stays correct and clients refetch
  on reconnect.
- **User events sent in the instant between a socket connecting and its channel subscription becoming active are
  lost.** This is within the best-effort design: the client refetches REST state on every (re)connect.
- **The start-up session check shows one 401 in the browser console for logged-out visitors.** The httpOnly cookie
  is invisible to JavaScript, so asking the server is the only way to know.
- **Safari is untested.** It has historically not stored Secure cookies on `http://localhost`. Chromium was used
  for every browser check; production would serve HTTPS anyway.
- **New accounts start at $0.** The web app has no deposit screen; the spec's Phase 2 list does not include one,
  and the API (`POST /api/account/deposits`) works.
- **Every instance reads every tick.** Each instance consumes all of `quotes.ticks` (spec 6.2). This is fine at
  ~2,750 ticks/s; the 10k-client load test is Phase 3.
