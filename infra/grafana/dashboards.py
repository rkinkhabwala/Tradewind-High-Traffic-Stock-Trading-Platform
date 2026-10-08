#!/usr/bin/env python3
"""Generates the provisioned Grafana dashboards (spec Phase 3). Run after editing; commit the JSON it writes.

    python3 infra/grafana/dashboards.py
"""
import json
import pathlib

OUT = pathlib.Path(__file__).parent / "provisioning" / "dashboards" / "json"
DS = {"type": "prometheus", "uid": "prometheus"}
RATE = "[$__rate_interval]"


def q(expr, legend):
    return {"expr": expr, "legendFormat": legend, "datasource": DS}


def quantiles(bucket, selector, by="", label=""):
    group = f"le{', ' + by if by else ''}"
    prefix = f"{label} " if label else ""
    suffix = f" {{{{{by}}}}}" if by else ""
    return [q(f'histogram_quantile({p}, sum by ({group}) (rate({bucket}{{{selector}}}{RATE})))', f"{prefix}p{int(p * 100)}{suffix}")
            for p in (0.5, 0.95, 0.99)]


def panel(title, targets, unit="short", w=12, h=8, description="", stack=False, kind="timeseries"):
    p = {
        "type": kind, "title": title, "description": description, "datasource": DS,
        "gridPos": {"w": w, "h": h},
        "targets": [dict(t, refId=chr(65 + i)) for i, t in enumerate(targets)],
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 1, "fillOpacity": 10,
                                                                "stacking": {"mode": "normal" if stack else "none"}}},
                        "overrides": []},
        "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]},
                    "tooltip": {"mode": "multi"}},
    }
    if kind == "stat":
        p["fieldConfig"]["defaults"].pop("custom")
        p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "area"}
    return p


def dashboard(uid, title, description, panels):
    x = y = row_h = 0
    for p in panels:
        w, h = p["gridPos"]["w"], p["gridPos"]["h"]
        if x + w > 24:
            x, y, row_h = 0, y + row_h, 0
        p["gridPos"].update(x=x, y=y)
        x, row_h = x + w, max(row_h, h)
    for i, p in enumerate(panels):
        p["id"] = i + 1
    return {"uid": uid, "title": title, "description": description, "tags": ["tradewind"], "timezone": "browser",
            "schemaVersion": 39, "refresh": "5s", "time": {"from": "now-15m", "to": "now"}, "editable": False,
            "panels": panels}


ORDERS_PLACE = 'routeId="orders-place"'

trading = dashboard("tradewind-trading", "Tradewind / Trading", "Order flow: throughput, latency, fills, rejections", [
    panel("Orders/sec by status", [q(f"sum by (status) (rate(orders_status_total{RATE}))", "{{status}}")],
          unit="reqps", description="Order status changes in order-service (ACCEPTED = placed and reserved)"),
    panel("POST /api/orders at the gateway, by HTTP status",
          [q(f"sum by (httpStatusCode) (rate(spring_cloud_gateway_requests_seconds_count{{{ORDERS_PLACE}}}{RATE}))", "{{httpStatusCode}}")],
          unit="reqps", description="429 = the per-user rate limit (intended under burst load)"),
    panel("POST /api/orders latency (gateway, end to end for the client)",
          quantiles("spring_cloud_gateway_requests_seconds_bucket", ORDERS_PLACE), unit="s",
          description="Spec target: p99 < 100 ms"),
    panel("POST /api/orders latency inside order-service",
          quantiles("http_server_requests_seconds_bucket", 'application="order-service",method="POST",uri="/api/orders"'),
          unit="s", description="Validation, reservation call, state change, outbox write"),
    panel("Order to fill (market orders)", quantiles("orders_fill_latency_seconds_bucket", 'type="MARKET"'), unit="s",
          description="Order created to FILLED recorded in order-service. Spec target: p99 < 500 ms"),
    panel("Reservation call to portfolio-service",
          quantiles("orders_reservation_duration_seconds_bucket", "", "outcome"), unit="s",
          description="The synchronous hop on the order path (bulkhead + circuit breaker)"),
    panel("Fills/sec", [q(f"sum(rate(execution_fills_total{RATE}))", "executions")], unit="reqps", w=8),
    panel("Ledger optimistic-lock conflicts/sec",
          [q(f"sum(rate(ledger_conflicts_total{RATE}))", "conflicts retried"),
           q(f"sum(rate(ledger_gave_up_total{RATE}))", "gave up (503)")], unit="reqps", w=8,
          description="Contention on hot accounts"),
    panel("Circuit breaker / bulkhead (order-service -> portfolio)",
          [q('sum by (state) (resilience4j_circuitbreaker_state{state=~"open|half_open"})', "breaker {{state}}"),
           q("sum(resilience4j_bulkhead_available_concurrent_calls)", "bulkhead free slots")], w=8),
    panel("User events pushed (order + portfolio services)",
          [q(f"sum by (application, outcome) (rate(user_events_total{RATE}))", "{{application}} {{outcome}}")],
          unit="reqps", w=12),
    panel("HTTP 5xx/sec by service",
          [q(f'sum by (application) (rate(http_server_requests_seconds_count{{status=~"5.."}}{RATE}))', "{{application}}")],
          unit="reqps", w=12),
])

streaming = dashboard("tradewind-streaming", "Tradewind / Streaming", "Ticks and WebSocket fan-out", [
    panel("WebSocket connections per instance", [q("sum by (instance) (stream_sessions)", "{{instance}}")],
          description="stream-service sessions; spec target 10,000 across instances", stack=True),
    panel("Healthy stream-service instances (gateway view)", [q("max(gateway_stream_instances_healthy)", "healthy")],
          kind="stat", w=6),
    panel("Total connections", [q("sum(stream_sessions)", "connections")], kind="stat", w=6),
    panel("Ticks/sec", [q(f"sum(rate(marketdata_ticks_produced_total{RATE}))", "produced (marketdata)"),
                        q(f"sum by (instance) (rate(stream_ticks_received_total{RATE}))", "received {{instance}}"),
                        q(f"sum(rate(marketdata_ticks_dropped_total{RATE}))", "dropped by simulator sink")],
          unit="reqps", description="Spec: 2,500/s sustained, 10,000/s for a 60 s burst"),
    panel("Tick to client socket", quantiles("stream_tick_to_client_seconds_bucket", ""), unit="s",
          description="Tick published by marketdata to quote written to a socket, conflation included. Spec: p99 < 250 ms"),
    panel("Tick publish to stream-service receipt (Kafka)",
          [q('max by (quantile) (stream_ticks_lag_seconds{quantile=~"0.5|0.99"})', "p{{quantile}}")], unit="s"),
    panel("Messages sent/sec per instance", [q(f"sum by (instance) (rate(stream_messages_sent_total{RATE}))", "{{instance}}")],
          unit="reqps", stack=True),
    panel("Conflation and backpressure",
          [q(f"sum(rate(stream_quotes_conflated_total{RATE}))", "conflated (newer quote within 250 ms)"),
           q(f"sum(rate(stream_quotes_dropped_total{RATE}))", "dropped stale (slow client)")], unit="reqps"),
    panel("Sessions closed by the server", [q(f"sum by (reason) (increase(stream_sessions_closed_total[1m]))", "{{reason}} /min")],
          description="slow = 4000 (could not keep up for 10 s), idle = 4001"),
    panel("User events relayed/sec", [q(f"sum(rate(stream_user_events_relayed_total{RATE}))", "relayed"),
                                      q("sum(stream_user_channels)", "subscribed account channels")]),
])

platform = dashboard("tradewind-platform", "Tradewind / Platform", "Kafka lag, database pools, JVM", [
    panel("Kafka consumer lag per group", [q("sum by (consumergroup) (kafka_consumergroup_lag)", "{{consumergroup}}")],
          description="From kafka-exporter. stream-service reads quotes.ticks without a group, so it is not here"),
    panel("Kafka consumer lag per group and topic",
          [q("sum by (consumergroup, topic) (kafka_consumergroup_lag)", "{{consumergroup}} / {{topic}}")]),
    panel("DB pool: active connections", [q("sum by (application, instance) (hikaricp_connections_active)", "{{application}} {{instance}}")]),
    panel("DB pool: threads waiting for a connection",
          [q("sum by (application, instance) (hikaricp_connections_pending)", "{{application}} {{instance}}")],
          description="Non-zero for long = pool too small (or queries too slow)"),
    panel("DB pool: max size", [q("max by (application) (hikaricp_connections_max)", "{{application}}")], w=8),
    panel("DB pool: connection acquire time (max)",
          [q("max by (application) (hikaricp_connections_acquire_seconds_max)", "{{application}}")], unit="s", w=8),
    panel("DB pool: timeouts/sec", [q(f"sum by (application) (rate(hikaricp_connections_timeout_total{RATE}))", "{{application}}")],
          unit="reqps", w=8),
    panel("JVM heap used", [q('sum by (application, instance) (jvm_memory_used_bytes{area="heap"})', "{{application}} {{instance}}")],
          unit="bytes"),
    panel("GC pause time (fraction of wall time)",
          [q(f"sum by (application, instance) (rate(jvm_gc_pause_seconds_sum{RATE}))", "{{application}} {{instance}}")],
          unit="percentunit"),
    panel("Process CPU", [q("max by (application, instance) (process_cpu_usage)", "{{application}} {{instance}}")],
          unit="percentunit"),
    panel("Live threads", [q("sum by (application, instance) (jvm_threads_live_threads)", "{{application}} {{instance}}")]),
])

OUT.mkdir(parents=True, exist_ok=True)
for d in (trading, streaming, platform):
    (OUT / f"{d['uid']}.json").write_text(json.dumps(d, indent=2) + "\n")
    print("wrote", OUT / f"{d['uid']}.json", f"({len(d['panels'])} panels)")
