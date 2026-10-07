package com.tradewind.execution.engine;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.execution.CancelRejected;
import com.tradewind.common.execution.ExecutionReported;
import com.tradewind.common.execution.OrderCanceled;
import com.tradewind.common.execution.OrderExpired;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.CancelRequested;
import com.tradewind.common.order.OrderAccepted;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;
import com.tradewind.common.outbox.OutboxWriter;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.execution.book.OrderBooks;
import com.tradewind.execution.book.RestingOrder;
import com.tradewind.execution.engine.ExecutionRepository.Outcome;
import com.tradewind.execution.matching.Matcher;
import com.tradewind.execution.quote.QuoteSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The single writer for the symbols of its partitions. Every event is applied in one database transaction
 * (fills, book rows, outbox events, processed-event marker); the in-memory book changes only after commit, so
 * a failed transaction leaves memory and Postgres consistent and the event can simply be retried.
 * Not thread-safe by design: only the consumer thread calls it.
 */
@Component
public class ExecutionEngine {

    /** New states of orders still resting and ids of orders that left the book, applied to memory after commit. */
    private record Changes(List<RestingOrder> updated, List<UUID> removed) {
        static Changes none() {
            return new Changes(new ArrayList<>(), new ArrayList<>());
        }
    }

    private final ExecutionRepository repository;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final OrderBooks books = new OrderBooks();
    private final QuoteSource quotes;
    private final MarketClock marketClock;
    private final Clock clock;
    private final Counter fills;

    ExecutionEngine(ExecutionRepository repository, OutboxWriter outbox, TransactionTemplate transactions,
                    QuoteSource quotes, MarketClock marketClock, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.outbox = outbox;
        this.transactions = transactions;
        this.quotes = quotes;
        this.marketClock = marketClock;
        this.clock = clock;
        this.fills = Counter.builder("execution.fills").register(meters);
    }

    // ---- events ----------------------------------------------------------------------------------------------

    public void onOrderAccepted(UUID eventId, OrderAccepted event, int partition) {
        Instant now = marketClock.now();
        LocalDate session = marketClock.sessionDate();
        boolean open = marketClock.isMarketOpen();
        RestingOrder incoming = new RestingOrder(event.orderId(), event.accountId(), event.symbol(), event.side(),
                event.type(), event.timeInForce(), event.limitPrice(), event.priceCap(), event.quantity(),
                event.quantity(), Quantity.ZERO, 1, session, partition, now);

        if (!open && (event.type() == OrderType.MARKET || event.timeInForce() == TimeInForce.DAY)) {
            // Arrived after the close (e.g. accepted at 15:59:59.9): a DAY order's session is already over.
            transactions.executeWithoutResult(status -> {
                if (repository.markProcessed(eventId, clock.instant())) {
                    expire(incoming, now);
                }
            });
            return;
        }
        Matcher.Result match = open ? currentQuote(event.symbol())
                .map(q -> Matcher.match(event.side() == OrderSide.BUY ? List.of(incoming) : List.of(),
                        event.side() == OrderSide.SELL ? List.of(incoming) : List.of(), q))
                .orElse(Matcher.Result.EMPTY) : Matcher.Result.EMPTY;

        Changes changes = transactions.execute(status -> {
            if (!repository.markProcessed(eventId, clock.instant())) {
                return null;    // duplicate delivery: already applied
            }
            repository.insertResting(incoming);
            Changes applied = apply(match, now);
            if (!applied.removed().contains(incoming.orderId())
                    && applied.updated().stream().noneMatch(o -> o.orderId().equals(incoming.orderId()))) {
                applied.updated().add(incoming);    // nothing traded: rests at full size
            }
            return applied;
        });
        commitToMemory(changes);
    }

    public void onCancelRequested(UUID eventId, CancelRequested event) {
        Instant now = marketClock.now();
        Changes changes = transactions.execute(status -> {
            if (!repository.markProcessed(eventId, clock.instant())) {
                return null;
            }
            Optional<RestingOrder> open = books.get(event.orderId());
            if (open.isPresent()) {
                cancel(open.get(), "USER_REQUESTED", now);
                return new Changes(List.of(), List.of(event.orderId()));
            }
            // The cancel lost the race (or never had an order): say why, exactly once.
            String reason = repository.outcomeOf(event.orderId())
                    .map(o -> "ALREADY_" + o.name())
                    .orElse("UNKNOWN_ORDER");
            CancelRejected rejected = new CancelRejected(event.orderId(), event.accountId(), event.symbol(), reason, now);
            publish(EventTypes.CANCEL_REJECTED, CancelRejected.VERSION, event.accountId(), event.orderId(), rejected);
            return Changes.none();
        });
        commitToMemory(changes);
    }

    public void onTick(QuoteTick tick) {
        books.recordTick(tick);
        if (!books.hasOrders(tick.symbol()) || !marketClock.isMarketOpen()) {
            return;
        }
        Matcher.Result match = Matcher.match(books.buys(tick.symbol()), books.sells(tick.symbol()), tick);
        if (match.isEmpty()) {
            return;
        }
        commitToMemory(transactions.execute(status -> apply(match, tick.marketTime())));
    }

    /**
     * Spec 6.5: at the simulated close, DAY orders expire. Any DAY order still open while the market is closed,
     * or left over from an earlier session, is past its session.
     */
    public void expireDayOrders() {
        boolean open = marketClock.isMarketOpen();
        LocalDate today = marketClock.sessionDate();
        List<RestingOrder> due = books.all().stream()
                .filter(o -> o.timeInForce() == TimeInForce.DAY)
                .filter(o -> !open || o.sessionDate().isBefore(today))
                .toList();
        if (due.isEmpty()) {
            return;
        }
        Instant now = marketClock.now();
        commitToMemory(transactions.execute(status -> {
            due.forEach(o -> expire(o, now));
            return new Changes(List.of(), due.stream().map(RestingOrder::orderId).toList());
        }));
    }

    public void onPartitionsAssigned(Collection<Integer> partitions) {
        repository.restingOn(partitions).forEach(books::add);
    }

    public void onPartitionsRevoked(Collection<Integer> partitions) {
        books.dropPartitions(new HashSet<>(partitions));
    }

    public int openOrders() {
        return books.size();
    }

    // ---- inside a transaction ----------------------------------------------------------------------------------

    private Changes apply(Matcher.Result match, Instant at) {
        Changes changes = Changes.none();
        for (Matcher.Fill fill : match.fills()) {
            RestingOrder before = fill.order();
            RestingOrder after = before.afterFill(fill.qty());
            int seq = before.nextSeq();
            UUID executionId = UUID.nameUUIDFromBytes(
                    ("execution:" + before.orderId() + ":" + seq).getBytes(StandardCharsets.UTF_8));
            repository.insertExecution(executionId, before, seq, fill.qty(), fill.price(), at);
            boolean terminal = after.remaining().isZero();
            if (terminal) {
                repository.close(before.orderId(), Outcome.FILLED, at);
                changes.removed().add(before.orderId());
            } else {
                repository.updateResting(after);
                changes.updated().add(after);
            }
            ExecutionReported event = new ExecutionReported(executionId, before.orderId(), before.accountId(),
                    before.symbol(), before.side(), seq, fill.qty(), fill.price(), after.filled(), after.remaining(),
                    terminal, at);
            publish(EventTypes.EXECUTION_REPORTED, ExecutionReported.VERSION, before.accountId(), before.orderId(), event);
            fills.increment();
        }
        for (RestingOrder order : match.collarCancels()) {
            cancel(order, "COLLAR_EXCEEDED", at);
            changes.removed().add(order.orderId());
        }
        return changes;
    }

    private void cancel(RestingOrder order, String reason, Instant at) {
        repository.close(order.orderId(), Outcome.CANCELED, at);
        publish(EventTypes.ORDER_CANCELED, OrderCanceled.VERSION, order.accountId(), order.orderId(),
                new OrderCanceled(order.orderId(), order.accountId(), order.symbol(), order.side(), order.remaining(),
                        order.filled(), reason, at));
    }

    private void expire(RestingOrder order, Instant at) {
        repository.close(order.orderId(), Outcome.EXPIRED, at);
        publish(EventTypes.ORDER_EXPIRED, OrderExpired.VERSION, order.accountId(), order.orderId(),
                new OrderExpired(order.orderId(), order.accountId(), order.symbol(), order.side(), order.remaining(),
                        order.filled(), order.sessionDate(), at));
    }

    /** executions is keyed by account so each account's money movements are consumed in order (spec 4). */
    private void publish(String type, int version, UUID accountId, UUID orderId, Object payload) {
        outbox.append(Topics.EXECUTIONS, orderId.toString(),
                EventEnvelope.of(type, version, accountId.toString(), payload, clock));
    }

    // ---- after commit --------------------------------------------------------------------------------------------

    private void commitToMemory(Changes changes) {
        if (changes == null) {
            return;
        }
        changes.removed().forEach(books::remove);
        changes.updated().forEach(books::add);
    }

    /** Spec: market orders fill against the current quote in Redis; fall back to the last tick seen. */
    private Optional<QuoteTick> currentQuote(String symbol) {
        return quotes.latest(symbol).or(() -> books.lastTick(symbol));
    }
}
