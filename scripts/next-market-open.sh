#!/usr/bin/env bash
# Moves the shared simulated market clock (FAST mode) to one minute before the next trading day's open (09:29
# New York), then restarts the services that read the clock. Use it when the simulated market has closed and you
# want to trade or run the E2E tests. Later in simulated time than everything recorded so far, so candles,
# snapshots and DAY-order expiry behave exactly as on a normal next day.
#
# Usage: scripts/next-market-open.sh        (needs docker and node)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

anchor="$(docker compose exec -T redis redis-cli GET market:clock-anchor | tr -d '\r')"
if [[ -z "$anchor" ]]; then
  echo "No shared clock anchor in Redis (is the stack running in FAST mode?)" >&2
  exit 1
fi

new_anchor="$(ANCHOR="$anchor" node --input-type=module -e '
const [realEpoch, simStart, speed] = process.env.ANCHOR.split("|");
const now = Date.now();
const marketNow = Date.parse(simStart) + (now - Date.parse(realEpoch)) * Number(speed);
// Offset of New York from UTC (minutes) at a given instant.
const nyOffset = (t) => {
  const p = Object.fromEntries(new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", hourCycle: "h23",
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
    .formatToParts(new Date(t)).map((x) => [x.type, x.value]));
  return (Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute) - Math.floor(t / 60000) * 60000) / 60000;
};
const ny = new Date(marketNow + nyOffset(marketNow) * 60000);      // wall-clock fields of New York, as UTC
let day = Date.UTC(ny.getUTCFullYear(), ny.getUTCMonth(), ny.getUTCDate() + 1);
while ([0, 6].includes(new Date(day).getUTCDay())) day += 86400000;
const local = day + (9 * 60 + 29) * 60000;                        // 09:29 New York, as if UTC
const open = local - nyOffset(local) * 60000;
console.log(`${new Date(now).toISOString()}|${new Date(open).toISOString().replace(".000Z", "Z")}|${speed}`);
console.error(`market time now ${new Date(marketNow).toISOString()} -> ${new Date(open).toISOString()} (09:29 New York)`);
')"

docker compose exec -T redis redis-cli SET market:clock-anchor "$new_anchor" > /dev/null
echo "anchor set to $new_anchor; restarting the clock-reading services"
docker compose restart marketdata-service order-service execution-service portfolio-service > /dev/null
for _ in $(seq 1 60); do
  healthy=$(docker compose ps marketdata-service order-service execution-service portfolio-service --format '{{.Health}}' | grep -c '^healthy$' || true)
  [[ "$healthy" == 4 ]] && { echo "ready: the market opens in about one minute"; exit 0; }
  sleep 2
done
echo "services did not become healthy in time; check docker compose ps" >&2
exit 1
