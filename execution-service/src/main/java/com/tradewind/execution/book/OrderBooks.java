package com.tradewind.execution.book;

import com.tradewind.common.order.OrderSide;
import com.tradewind.common.quote.QuoteTick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * In-memory per-symbol limit-order index (spec 5), sorted by price then time. Only the engine thread touches it
 * (single writer per symbol), so it is deliberately not thread-safe. Postgres ({@code resting_orders}) is the
 * source of truth; this is rebuilt from it whenever partitions are assigned.
 */
public class OrderBooks {

    static final class SymbolBook {
        final TreeSet<RestingOrder> buys = new TreeSet<>(RestingOrder.BUY_PRIORITY);
        final TreeSet<RestingOrder> sells = new TreeSet<>(RestingOrder.SELL_PRIORITY);
    }

    private final Map<String, SymbolBook> books = new HashMap<>();
    private final Map<UUID, RestingOrder> byId = new HashMap<>();
    private final Map<String, QuoteTick> lastTicks = new HashMap<>();

    public void add(RestingOrder order) {
        remove(order.orderId());
        SymbolBook book = books.computeIfAbsent(order.symbol(), s -> new SymbolBook());
        (order.side() == OrderSide.BUY ? book.buys : book.sells).add(order);
        byId.put(order.orderId(), order);
    }

    public void remove(UUID orderId) {
        RestingOrder existing = byId.remove(orderId);
        if (existing != null) {
            SymbolBook book = books.get(existing.symbol());
            (existing.side() == OrderSide.BUY ? book.buys : book.sells).remove(existing);
        }
    }

    public Optional<RestingOrder> get(UUID orderId) {
        return Optional.ofNullable(byId.get(orderId));
    }

    public List<RestingOrder> buys(String symbol) {
        SymbolBook book = books.get(symbol);
        return book == null ? List.of() : List.copyOf(book.buys);
    }

    public List<RestingOrder> sells(String symbol) {
        SymbolBook book = books.get(symbol);
        return book == null ? List.of() : List.copyOf(book.sells);
    }

    public boolean hasOrders(String symbol) {
        SymbolBook book = books.get(symbol);
        return book != null && !(book.buys.isEmpty() && book.sells.isEmpty());
    }

    public Collection<RestingOrder> all() {
        return new ArrayList<>(byId.values());
    }

    /** Drops every order that arrived on one of {@code partitions} (they now belong to another instance). */
    public void dropPartitions(Set<Integer> partitions) {
        for (RestingOrder order : all()) {
            if (partitions.contains(order.partition())) {
                remove(order.orderId());
            }
        }
    }

    public void recordTick(QuoteTick tick) {
        lastTicks.put(tick.symbol(), tick);
    }

    public Optional<QuoteTick> lastTick(String symbol) {
        return Optional.ofNullable(lastTicks.get(symbol));
    }

    public int size() {
        return byId.size();
    }
}
