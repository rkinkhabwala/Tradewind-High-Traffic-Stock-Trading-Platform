package com.tradewind.common.order;

public enum TimeInForce {
    /** Expires at the end of the session (simulated 16:00 New York). */
    DAY,
    /** Good till canceled: survives session close. Limit orders only. */
    GTC
}
