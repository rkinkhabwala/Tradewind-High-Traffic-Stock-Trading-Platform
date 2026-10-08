package com.tradewind.stream.session;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * This instance's sessions and the in-memory index {@code symbol -> sessions} (spec 6.2). A tick is serialized
 * once and handed to each subscribed session; sessions are checked for slowness and idleness twice a second.
 */
@Component
public class SessionRegistry implements SmartLifecycle {

    static final Duration CHECK_INTERVAL = Duration.ofMillis(500);

    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<ClientSession>> bySymbol = new ConcurrentHashMap<>();
    private final Scheduler scheduler;
    private volatile Disposable checks;

    SessionRegistry(@Qualifier("streamScheduler") Scheduler scheduler, MeterRegistry meters) {
        this.scheduler = scheduler;
        Gauge.builder("stream.sessions", sessions, Set::size).register(meters);
        Gauge.builder("stream.subscribed.symbols", bySymbol, Map::size).register(meters);
    }

    public void register(ClientSession session) {
        sessions.add(session);
    }

    public void unregister(ClientSession session) {
        sessions.remove(session);
        session.removeSymbols(session.symbols()).forEach(symbol -> unindex(symbol, session));
    }

    /** The newly added symbols, or null if the request would exceed the session's subscription limit. */
    public List<String> subscribe(ClientSession session, Set<String> symbols) {
        List<String> added = session.addSymbols(symbols);
        if (added != null) {
            added.forEach(symbol -> bySymbol.computeIfAbsent(symbol, s -> ConcurrentHashMap.newKeySet()).add(session));
        }
        return added;
    }

    public List<String> unsubscribe(ClientSession session, Set<String> symbols) {
        List<String> removed = session.removeSymbols(symbols);
        removed.forEach(symbol -> unindex(symbol, session));
        return removed;
    }

    /** Whether anyone on this instance wants the symbol; lets the consumer skip serializing unwanted ticks. */
    public boolean hasSubscribers(String symbol) {
        Set<ClientSession> subscribers = bySymbol.get(symbol);
        return subscribers != null && !subscribers.isEmpty();
    }

    /** {@code publishedAtMillis}: wall-clock publish time of the tick, for the tick-to-client latency metric. */
    public void publishQuote(String symbol, String json, long publishedAtMillis) {
        Set<ClientSession> subscribers = bySymbol.get(symbol);
        if (subscribers != null) {
            subscribers.forEach(session -> session.offerQuote(symbol, json, publishedAtMillis));
        }
    }

    public Collection<ClientSession> sessions() {
        return List.copyOf(sessions);
    }

    private void unindex(String symbol, ClientSession session) {
        bySymbol.computeIfPresent(symbol, (s, subscribers) -> {
            subscribers.remove(session);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    @Override
    public void start() {
        long ms = CHECK_INTERVAL.toMillis();
        checks = scheduler.schedulePeriodically(() -> sessions.forEach(ClientSession::check), ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        Disposable d = checks;
        if (d != null) {
            d.dispose();
        }
        checks = null;
    }

    @Override
    public boolean isRunning() {
        return checks != null;
    }
}
