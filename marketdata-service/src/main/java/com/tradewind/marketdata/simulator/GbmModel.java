package com.tradewind.marketdata.simulator;

/**
 * Geometric Brownian motion with optional jumps:
 * {@code S' = S * exp((mu - sigma^2/2) dt + sigma sqrt(dt) Z + J)}, where J ~ N(0, jumpSigma) when a jump occurs.
 * Time is measured in trading years of simulated market time, so volatility scales correctly under FAST_CLOCK.
 */
final class GbmModel {

    static final double TRADING_SECONDS_PER_YEAR = 252 * 6.5 * 3600;
    static final double MIN_PRICE = 0.05;

    private GbmModel() {
    }

    static double step(double price, double drift, double sigma, double dtYears, double z, double jumpLogReturn) {
        double logReturn = (drift - 0.5 * sigma * sigma) * dtYears + sigma * Math.sqrt(dtYears) * z + jumpLogReturn;
        return Math.max(MIN_PRICE, price * Math.exp(logReturn));
    }
}
