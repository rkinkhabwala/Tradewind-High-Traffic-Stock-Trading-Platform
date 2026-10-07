package com.tradewind.portfolio.position;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Owned shares of one symbol (available plus reserved for open sells) and the weighted average cost of the buys
 * that built it. Sells reduce the quantity and leave the average cost unchanged (approved decision 6).
 */
public record Position(UUID accountId, String symbol, Quantity qty, BigDecimal avgCost) {

    public static final int AVG_COST_SCALE = 10;

    public static Position firstBuy(UUID accountId, String symbol, Quantity qty, Price price) {
        return new Position(accountId, symbol, qty, price.value().setScale(AVG_COST_SCALE, RoundingMode.UNNECESSARY));
    }

    public Position afterBuy(Quantity bought, Price price) {
        Quantity total = qty.plus(bought);
        BigDecimal cost = qty.value().multiply(avgCost).add(bought.value().multiply(price.value()));
        return new Position(accountId, symbol, total, cost.divide(total.value(), AVG_COST_SCALE, RoundingMode.HALF_EVEN));
    }

    public Position afterSell(Quantity sold) {
        return new Position(accountId, symbol, qty.minus(sold), avgCost);
    }

    /** Average cost as a 4-decimal price for display. */
    public Price displayAvgCost() {
        return Price.of(avgCost.setScale(Price.SCALE, RoundingMode.HALF_EVEN));
    }
}
