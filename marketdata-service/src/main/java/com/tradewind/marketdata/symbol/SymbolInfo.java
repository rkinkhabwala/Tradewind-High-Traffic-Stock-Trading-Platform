package com.tradewind.marketdata.symbol;

import com.tradewind.common.money.Price;

import java.math.BigDecimal;

/** A tradable symbol. {@code volatility} is annualised (0.25 = 25%). */
public record SymbolInfo(String symbol, String name, String sector, Price basePrice, BigDecimal volatility) {
}
