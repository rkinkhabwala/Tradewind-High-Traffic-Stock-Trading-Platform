package com.tradewind.common.event;

/** {@code eventType} values. Payload records live next to the topic that carries them. */
public final class EventTypes {

    public static final String QUOTE_TICK = "QuoteTick";
    public static final String ORDER_ACCEPTED = "OrderAccepted";
    public static final String CANCEL_REQUESTED = "CancelRequested";
    public static final String EXECUTION_REPORTED = "ExecutionReported";
    public static final String ORDER_CANCELED = "OrderCanceled";
    public static final String ORDER_EXPIRED = "OrderExpired";
    public static final String CANCEL_REJECTED = "CancelRejected";
    public static final String PORTFOLIO_UPDATED = "PortfolioUpdated";

    private EventTypes() {
    }
}
