package com.tradewind.portfolio.web;

import com.tradewind.common.money.Money;
import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.portfolio.activity.ActivityRepository;
import com.tradewind.portfolio.activity.ActivityRepository.Activity;
import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerAmounts;
import com.tradewind.portfolio.ledger.LedgerService;
import com.tradewind.portfolio.position.Position;
import com.tradewind.portfolio.position.PositionRepository;
import com.tradewind.portfolio.quote.QuoteReader;
import com.tradewind.portfolio.snapshot.PortfolioHistory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public portfolio endpoints (spec 7), routed by the gateway. Amounts are integer cents; prices and quantities are
 * decimal strings. Market values use the latest quotes (rounded half-even to the cent for display only); if no
 * quote is available the live fields are null rather than the request failing.
 */
@RestController
class PortfolioController {

    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 200;

    record PositionView(String symbol, String qty, String avgCost, String last, Long marketValueCents,
                        Long unrealizedPnlCents, Long dayChangeCents) {
    }

    /** buyingPowerCents = available cash; reserved cash is already committed to open buy orders. */
    record PortfolioView(UUID accountId, long cashCents, long cashReservedCents, long buyingPowerCents,
                         List<PositionView> positions, Long positionsValueCents, Long totalValueCents,
                         Long dayChangeCents, boolean livePrices) {
    }

    record ActivityView(UUID id, String type, String symbol, String side, String qty, String price,
                        long amountCents, Instant occurredAt) {
    }

    record ActivityPage(List<ActivityView> items, String nextCursor) {
    }

    record HistoryView(String range, List<PortfolioHistory.Point> points) {
    }

    private final LedgerService ledger;
    private final PositionRepository positions;
    private final ActivityRepository activity;
    private final QuoteReader quotes;
    private final PortfolioHistory history;
    private final TransactionTemplate readOnly;

    PortfolioController(LedgerService ledger, PositionRepository positions, ActivityRepository activity,
                        QuoteReader quotes, PortfolioHistory history, TransactionTemplate transactions) {
        this.ledger = ledger;
        this.positions = positions;
        this.activity = activity;
        this.quotes = quotes;
        this.history = history;
        this.readOnly = new TransactionTemplate(transactions.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    @GetMapping("/api/portfolio")
    PortfolioView portfolio(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId) {
        UUID account = Identity.require(accountId);
        // One snapshot of cash and positions; quotes are read afterwards, outside the transaction.
        record Snapshot(Money cash, Money reserved, List<Position> held) {
        }
        Snapshot snapshot = readOnly.execute(status -> new Snapshot(
                LedgerAmounts.toMoney(ledger.balanceOf(LedgerAccountKey.cash(account))),
                LedgerAmounts.toMoney(ledger.balanceOf(LedgerAccountKey.cashReserved(account))),
                positions.forAccount(account)));
        Map<String, StoredQuote> latest = quotes.latest(snapshot.held().stream().map(Position::symbol).toList());

        boolean live = latest.keySet().containsAll(snapshot.held().stream().map(Position::symbol).toList());
        List<PositionView> views = new ArrayList<>();
        long positionsValue = 0;
        long dayChange = 0;
        for (Position p : snapshot.held()) {
            StoredQuote q = latest.get(p.symbol());
            if (q == null) {
                views.add(new PositionView(p.symbol(), p.qty().toJson(), p.displayAvgCost().toJson(), null, null, null, null));
                continue;
            }
            Price last = q.tick().last();
            BigDecimal value = p.qty().value().multiply(last.value());
            long valueCents = Money.roundHalfEven(value).cents();
            long costCents = Money.roundHalfEven(p.qty().value().multiply(p.avgCost())).cents();
            long dayCents = Money.roundHalfEven(p.qty().value().multiply(last.value().subtract(q.prevClose().value()))).cents();
            views.add(new PositionView(p.symbol(), p.qty().toJson(), p.displayAvgCost().toJson(), last.toJson(),
                    valueCents, valueCents - costCents, dayCents));
            positionsValue += valueCents;
            dayChange += dayCents;
        }
        long cash = snapshot.cash().cents();
        long reserved = snapshot.reserved().cents();
        return new PortfolioView(account, cash, reserved, cash, views,
                live ? positionsValue : null, live ? cash + reserved + positionsValue : null,
                live ? dayChange : null, live);
    }

    /** Portfolio value series from snapshots (5-simulated-minute resolution, downsampled for long ranges). */
    @GetMapping("/api/portfolio/history")
    HistoryView history(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                        @RequestParam(defaultValue = "1D") String range) {
        PortfolioHistory.Range parsed = PortfolioHistory.Range.parse(range);
        return new HistoryView(parsed.code, history.history(Identity.require(accountId), parsed));
    }

    @GetMapping("/api/activity")
    ActivityPage activity(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                          @RequestParam(required = false) String cursor,
                          @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        UUID account = Identity.require(accountId);
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be 1-" + MAX_PAGE);
        }
        List<Activity> page = activity.page(account, decode(cursor), limit);
        String next = page.size() == limit ? encode(page.getLast()) : null;
        return new ActivityPage(page.stream().map(a -> new ActivityView(a.id(), a.type().name(), a.symbol(),
                a.side() == null ? null : a.side().name(), a.qty() == null ? null : a.qty().toJson(),
                a.price() == null ? null : a.price().toJson(), a.amount().cents(), a.occurredAt())).toList(), next);
    }

    private static String encode(Activity last) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((last.occurredAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static ActivityRepository.Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            return new ActivityRepository.Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }
}
