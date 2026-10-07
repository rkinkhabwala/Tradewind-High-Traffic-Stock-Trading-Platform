package com.tradewind.execution.matching;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.execution.book.RestingOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Fills orders against a simulated quote, like a broker routing to market makers (spec 11): buys at the ask,
 * sells at the bid, sharing the displayed size in price-time priority. Pure function, no state.
 * Market buys whose remainder is now priced above their cap (the reserved 5% collar) are canceled instead.
 */
public final class Matcher {

    public record Fill(RestingOrder order, Quantity qty, Price price) {
    }

    public record Result(List<Fill> fills, List<RestingOrder> collarCancels) {
        public static final Result EMPTY = new Result(List.of(), List.of());

        public boolean isEmpty() {
            return fills.isEmpty() && collarCancels.isEmpty();
        }
    }

    private Matcher() {
    }

    /** {@code buys}/{@code sells} must be in priority order (see RestingOrder comparators). */
    public static Result match(List<RestingOrder> buys, List<RestingOrder> sells, QuoteTick quote) {
        List<Fill> fills = new ArrayList<>();
        List<RestingOrder> cancels = new ArrayList<>();

        Quantity askAvailable = shares(quote.askSize());
        for (RestingOrder buy : buys) {
            if (buy.type() == OrderType.MARKET && !buy.acceptsBuyAt(quote.ask())) {
                cancels.add(buy);
            } else if (askAvailable.isPositive() && buy.acceptsBuyAt(quote.ask())) {
                Quantity qty = buy.remaining().min(askAvailable);
                fills.add(new Fill(buy, qty, quote.ask()));
                askAvailable = askAvailable.minus(qty);
            }
        }

        Quantity bidAvailable = shares(quote.bidSize());
        for (RestingOrder sell : sells) {
            if (!bidAvailable.isPositive() || !sell.acceptsSellAt(quote.bid())) {
                break;  // sorted best-first: nothing after this one can trade either
            }
            Quantity qty = sell.remaining().min(bidAvailable);
            fills.add(new Fill(sell, qty, quote.bid()));
            bidAvailable = bidAvailable.minus(qty);
        }
        return new Result(fills, cancels);
    }

    private static Quantity shares(long displayedSize) {
        return Quantity.of(BigDecimal.valueOf(displayedSize));
    }
}
