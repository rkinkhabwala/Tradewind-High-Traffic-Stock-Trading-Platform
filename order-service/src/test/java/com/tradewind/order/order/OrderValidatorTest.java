package com.tradewind.order.order;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.order.marketdata.MarketDataReader;
import com.tradewind.order.order.OrderDtos.PlaceOrderRequest;
import com.tradewind.order.order.OrderValidator.OrderValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderValidatorTest {

    private static final Instant MONDAY_10AM = Instant.parse("2026-01-05T15:00:00Z");
    private static final Instant SATURDAY = Instant.parse("2026-01-10T15:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(MONDAY_10AM);
    private final Map<String, StoredQuote> quotes = Map.of("AAPL", new StoredQuote(new QuoteTick("AAPL",
            Price.of("199.99"), Price.of("200.01"), Price.of("200.00"), 100, 100, 0, MONDAY_10AM), Price.of("198")));
    private final MarketDataReader marketData = new MarketDataReader() {
        @Override
        public boolean isKnownSymbol(String symbol) {
            return symbol.equals("AAPL") || symbol.equals("NOQUOTE");
        }

        @Override
        public Optional<StoredQuote> quote(String symbol) {
            return Optional.ofNullable(quotes.get(symbol));
        }
    };
    private final MarketClock clock = now::get;
    private final OrderValidator validator = new OrderValidator(marketData, clock);

    private static PlaceOrderRequest order(String symbol, OrderType type, TimeInForce tif, String qty, String limit) {
        return new PlaceOrderRequest(symbol, OrderSide.BUY, type, tif, Quantity.of(qty),
                limit == null ? null : Price.of(limit));
    }

    private void assertRejected(PlaceOrderRequest request, String code) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(OrderValidationException.class)
                .extracting(e -> ((OrderValidationException) e).code()).isEqualTo(code);
    }

    @Test
    void validOrdersReturnTheQuote() {
        assertThat(validator.validate(order("aapl", OrderType.MARKET, null, "1.5", null)).tick().ask())
                .isEqualTo(Price.of("200.01"));
        assertThatCode(() -> validator.validate(order("AAPL", OrderType.LIMIT, TimeInForce.DAY, "1", "190.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void fatFingerBandIsFiftyPercentOfLastInclusive() {
        assertThatCode(() -> validator.validate(order("AAPL", OrderType.LIMIT, null, "1", "300.00"))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(order("AAPL", OrderType.LIMIT, null, "1", "100.00"))).doesNotThrowAnyException();
        assertRejected(order("AAPL", OrderType.LIMIT, null, "1", "300.01"), "PRICE_OUT_OF_BAND");
        assertRejected(order("AAPL", OrderType.LIMIT, null, "1", "99.99"), "PRICE_OUT_OF_BAND");
    }

    @Test
    void marketHoursOnlyGtcLimitOrdersWhenClosed() {
        now.set(SATURDAY);
        assertRejected(order("AAPL", OrderType.MARKET, null, "1", null), "MARKET_CLOSED");
        assertRejected(order("AAPL", OrderType.LIMIT, TimeInForce.DAY, "1", "190"), "MARKET_CLOSED");
        assertThatCode(() -> validator.validate(order("AAPL", OrderType.LIMIT, TimeInForce.GTC, "1", "190")))
                .doesNotThrowAnyException();
    }

    @Test
    void structuralRules() {
        assertRejected(order("AAPL", OrderType.MARKET, null, "0", null), "INVALID_QUANTITY");
        assertRejected(order("AAPL", OrderType.MARKET, null, "1", "200"), "INVALID_ORDER");
        assertRejected(order("AAPL", OrderType.MARKET, TimeInForce.GTC, "1", null), "INVALID_ORDER");
        assertRejected(order("AAPL", OrderType.LIMIT, null, "1", null), "INVALID_ORDER");
        assertRejected(order("AAPL", OrderType.LIMIT, null, "1", "200.005"), "INVALID_TICK");
        assertRejected(order("NOPE", OrderType.MARKET, null, "1", null), "UNKNOWN_SYMBOL");
    }

    @Test
    void missingQuoteIsRetryableNotARejection() {
        assertThatThrownBy(() -> validator.validate(order("NOQUOTE", OrderType.MARKET, null, "1", null)))
                .isInstanceOf(OrderValidator.NoPriceException.class);
    }
}
