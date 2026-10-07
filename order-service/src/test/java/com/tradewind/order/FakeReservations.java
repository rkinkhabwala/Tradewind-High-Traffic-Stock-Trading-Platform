package com.tradewind.order;

import com.tradewind.common.money.Quantity;
import com.tradewind.order.portfolio.ReservationClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Behaves like portfolio-service's reservation API (idempotent per order, release tombstones), but scriptable. */
public class FakeReservations implements ReservationClient {

    public enum Mode { RESERVE, INSUFFICIENT, UNAVAILABLE }

    public volatile Mode mode = Mode.RESERVE;
    public volatile Duration delay = Duration.ZERO;
    public final AtomicInteger reserveCalls = new AtomicInteger();
    public final Map<UUID, Long> reservedCash = new ConcurrentHashMap<>();
    public final List<UUID> released = new CopyOnWriteArrayList<>();
    private final Set<UUID> tombstones = ConcurrentHashMap.newKeySet();

    public void reset() {
        mode = Mode.RESERVE;
        delay = Duration.ZERO;
    }

    @Override
    public Outcome reserveCash(UUID orderId, UUID accountId, long cents) {
        Outcome outcome = reserve(orderId);
        if (outcome instanceof Reserved) {
            reservedCash.put(orderId, cents);
        }
        return outcome;
    }

    @Override
    public Outcome reserveShares(UUID orderId, UUID accountId, String symbol, Quantity quantity) {
        return reserve(orderId);
    }

    @Override
    public void release(UUID orderId) {
        if (mode == Mode.UNAVAILABLE) {
            throw new ReservationUnavailableException("simulated outage", null);
        }
        if (!reservedCash.containsKey(orderId)) {
            tombstones.add(orderId);
        }
        released.add(orderId);
    }

    private Outcome reserve(UUID orderId) {
        reserveCalls.incrementAndGet();
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return switch (mode) {
            case UNAVAILABLE -> throw new ReservationUnavailableException("simulated outage", null);
            case INSUFFICIENT -> new Insufficient("INSUFFICIENT_BUYING_POWER");
            case RESERVE -> tombstones.contains(orderId) ? new AlreadyReleased() : new Reserved();
        };
    }
}
