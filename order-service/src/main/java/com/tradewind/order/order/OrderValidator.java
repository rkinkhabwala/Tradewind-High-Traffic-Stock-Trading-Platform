package com.tradewind.order.order;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.money.Price;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.order.marketdata.MarketDataReader;
import com.tradewind.order.order.OrderDtos.PlaceOrderRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Spec 6.3 step 2: symbol exists, market open (or GTC limit), qty > 0, limit price within 50% of last.
 * Also: limit prices on the $0.01 tick, market orders are DAY only and carry no limit price.
 */
@Component
public class OrderValidator {

    static final BigDecimal FAT_FINGER_BAND = new BigDecimal("0.50");

    /** A rule the order breaks: answered with 422 and no order row is created. */
    public static class OrderValidationException extends RuntimeException {
        private final String code;

        public OrderValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** No price yet for the symbol (e.g. marketdata just started): retryable 503, nothing recorded. */
    public static class NoPriceException extends RuntimeException {
        public NoPriceException(String symbol) {
            super("No price available yet for " + symbol);
        }
    }

    private final MarketDataReader marketData;
    private final MarketClock clock;

    OrderValidator(MarketDataReader marketData, MarketClock clock) {
        this.marketData = marketData;
        this.clock = clock;
    }

    /** Returns the latest quote, which the reservation is sized from. */
    public StoredQuote validate(PlaceOrderRequest request) {
        if (!request.qty().isPositive()) {
            throw new OrderValidationException("INVALID_QUANTITY", "qty must be greater than zero");
        }
        TimeInForce tif = request.timeInForceOrDefault();
        if (request.type() == OrderType.MARKET) {
            if (request.limitPrice() != null) {
                throw new OrderValidationException("INVALID_ORDER", "market orders must not have a limitPrice");
            }
            if (tif != TimeInForce.DAY) {
                throw new OrderValidationException("INVALID_ORDER", "market orders are DAY orders");
            }
        } else {
            if (request.limitPrice() == null) {
                throw new OrderValidationException("INVALID_ORDER", "limit orders need a limitPrice");
            }
            if (request.limitPrice().value().stripTrailingZeros().scale() > 2) {
                throw new OrderValidationException("INVALID_TICK", "limitPrice must be in whole cents");
            }
        }
        if (!marketData.isKnownSymbol(request.normalizedSymbol())) {
            throw new OrderValidationException("UNKNOWN_SYMBOL", "Unknown symbol " + request.normalizedSymbol());
        }
        boolean gtcLimit = request.type() == OrderType.LIMIT && tif == TimeInForce.GTC;
        if (!gtcLimit && !clock.isMarketOpen()) {
            throw new OrderValidationException("MARKET_CLOSED",
                    "The market is closed; only GTC limit orders are accepted outside 9:30-16:00 ET");
        }
        StoredQuote quote = marketData.quote(request.normalizedSymbol())
                .orElseThrow(() -> new NoPriceException(request.normalizedSymbol()));
        if (request.type() == OrderType.LIMIT) {
            checkFatFinger(request.limitPrice(), quote.tick().last());
        }
        return quote;
    }

    static void checkFatFinger(Price limit, Price last) {
        BigDecimal band = last.value().multiply(FAT_FINGER_BAND);
        if (limit.value().subtract(last.value()).abs().compareTo(band) > 0) {
            throw new OrderValidationException("PRICE_OUT_OF_BAND",
                    "limitPrice " + limit + " is more than 50% away from the last price " + last);
        }
    }
}
