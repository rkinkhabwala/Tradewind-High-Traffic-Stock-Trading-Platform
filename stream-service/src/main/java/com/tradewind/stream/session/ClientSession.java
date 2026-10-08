package com.tradewind.stream.session;

import com.tradewind.stream.StreamProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One connected client's outbound side (spec 6.2), independent of the WebSocket transport.
 * <ul>
 *   <li><b>Conflation:</b> quotes collect in {@code pending} (latest per symbol wins) and are released at most once
 *   per conflation interval, so a symbol is sent at most once per interval.</li>
 *   <li><b>Backpressure:</b> released quotes wait in {@code ready}, again one per symbol, until the transport asks
 *   for more. A newer quote replaces a waiting one, so stale quotes are dropped first and memory is bounded by the
 *   number of subscriptions. Other messages wait in a bounded queue and are never dropped.</li>
 *   <li><b>Slow clients:</b> a client that has had messages waiting without taking any for the slow-client timeout,
 *   or overflows the queue, is closed with code 4000 so it reconnects. A client silent for the idle timeout is
 *   closed with 4001.</li>
 * </ul>
 */
public final class ClientSession {

    public static final int CLOSE_SLOW = 4000;
    public static final int CLOSE_IDLE = 4001;

    /** How the session closes its connection; implemented by the WebSocket handler. */
    public interface Transport {
        void close(int code, String reason);
    }

    private final String id;
    private final UUID userId;
    private final UUID accountId;
    private final StreamProperties properties;
    private final Scheduler scheduler;
    private final Transport transport;
    private final StreamMetrics metrics;
    private final Set<String> symbols = ConcurrentHashMap.newKeySet();
    private final Flux<String> outbound;

    // Guarded by this.
    private final Map<String, String> pending = new HashMap<>();
    private final LinkedHashMap<String, String> ready = new LinkedHashMap<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<String> liveSeen = new HashSet<>();
    private boolean flushScheduled;
    private long lastFlushAt = Long.MIN_VALUE / 2;

    private final AtomicInteger wip = new AtomicInteger();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile FluxSink<String> sink;
    private volatile long lastProgressAt;
    private volatile long lastInboundAt;

    public ClientSession(String id, UUID userId, UUID accountId, StreamProperties properties, Scheduler scheduler,
                         Transport transport, StreamMetrics metrics) {
        this.id = id;
        this.userId = userId;
        this.accountId = accountId;
        this.properties = properties;
        this.scheduler = scheduler;
        this.transport = transport;
        this.metrics = metrics;
        this.lastProgressAt = now();
        this.lastInboundAt = now();
        this.outbound = Flux.create(s -> {
            sink = s;
            s.onRequest(n -> drain());
            drain();
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    public String id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID accountId() {
        return accountId;
    }

    /** Text frames for the transport, emitted only as fast as the transport requests them. */
    public Flux<String> outbound() {
        return outbound;
    }

    public boolean isClosing() {
        return closing.get();
    }

    public Set<String> symbols() {
        return new TreeSet<>(symbols);
    }

    /** Adds symbols, all or nothing within the subscription limit. Returns the newly added ones, or null if over. */
    synchronized List<String> addSymbols(Set<String> requested) {
        List<String> added = requested.stream().filter(s -> !symbols.contains(s)).sorted().toList();
        if (symbols.size() + added.size() > properties.maxSubscriptions()) {
            return null;
        }
        symbols.addAll(added);
        added.forEach(liveSeen::remove);
        return added;
    }

    synchronized List<String> removeSymbols(Set<String> requested) {
        List<String> removed = requested.stream().filter(symbols::remove).sorted().toList();
        removed.forEach(s -> {
            pending.remove(s);
            ready.remove(s);
        });
        return removed;
    }

    /** A live quote (pre-serialized, shared by all sessions). Called from the tick consumer thread. */
    public void offerQuote(String symbol, String json) {
        synchronized (this) {
            if (closing.get() || !symbols.contains(symbol)) {
                return;
            }
            liveSeen.add(symbol);
            if (pending.put(symbol, json) != null) {
                metrics.conflated.increment();
            }
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
            long delay = Math.max(0, lastFlushAt + properties.conflationInterval().toMillis() - now());
            scheduler.schedule(this::flush, delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The latest stored quote, sent right after subscribing so the client does not wait for the next tick.
     * Ignored if a live quote for the symbol has already arrived, since that one is newer.
     */
    public void offerSnapshot(String symbol, String json) {
        synchronized (this) {
            if (closing.get() || !symbols.contains(symbol) || liveSeen.contains(symbol)) {
                return;
            }
            ready.putIfAbsent(symbol, json);
        }
        drain();
    }

    /** Any non-quote message. Never dropped: if the queue overflows the client is too slow and is closed. */
    public void send(String json) {
        boolean overflow;
        synchronized (this) {
            if (closing.get()) {
                return;
            }
            overflow = queue.size() >= properties.sendQueueLimit();
            if (!overflow) {
                queue.add(json);
            }
        }
        if (overflow) {
            close(CLOSE_SLOW, "slow consumer");
        } else {
            drain();
        }
    }

    public void onInbound() {
        lastInboundAt = now();
    }

    /** Called periodically: closes slow and idle clients. */
    public void check() {
        if (closing.get()) {
            return;
        }
        long now = now();
        boolean backlog;
        synchronized (this) {
            backlog = !queue.isEmpty() || !ready.isEmpty();
        }
        if (!backlog) {
            lastProgressAt = now;
        } else if (now - lastProgressAt > properties.slowClientTimeout().toMillis()) {
            close(CLOSE_SLOW, "slow consumer");
            return;
        }
        if (now - lastInboundAt > properties.idleTimeout().toMillis()) {
            close(CLOSE_IDLE, "idle");
        }
    }

    public void close(int code, String reason) {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            pending.clear();
            ready.clear();
            queue.clear();
        }
        if (code == CLOSE_SLOW) {
            metrics.closedSlow.increment();
        } else if (code == CLOSE_IDLE) {
            metrics.closedIdle.increment();
        }
        transport.close(code, reason);
    }

    private void flush() {
        synchronized (this) {
            flushScheduled = false;
            lastFlushAt = now();
            for (Map.Entry<String, String> e : pending.entrySet()) {
                if (ready.put(e.getKey(), e.getValue()) != null) {
                    metrics.staleDropped.increment();
                }
            }
            pending.clear();
        }
        drain();
    }

    /** Emits waiting messages while the transport has demand. Serialized without holding a lock while emitting. */
    private void drain() {
        FluxSink<String> s = sink;
        if (s == null || wip.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        do {
            while (s.requestedFromDownstream() > 0 && !s.isCancelled() && !closing.get()) {
                String next = poll();
                if (next == null) {
                    break;
                }
                s.next(next);
                metrics.sent.increment();
                lastProgressAt = now();
            }
            missed = wip.addAndGet(-missed);
        } while (missed != 0);
    }

    private synchronized String poll() {
        String next = queue.poll();
        if (next == null && !ready.isEmpty()) {
            Iterator<Map.Entry<String, String>> it = ready.entrySet().iterator();
            next = it.next().getValue();
            it.remove();
        }
        return next;
    }

    private long now() {
        return scheduler.now(TimeUnit.MILLISECONDS);
    }
}
