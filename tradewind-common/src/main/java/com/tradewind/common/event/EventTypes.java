package com.tradewind.common.event;

/** {@code eventType} values. Payload records live next to the topic that carries them. */
public final class EventTypes {

    public static final String QUOTE_TICK = "QuoteTick";
    public static final String ORDER_ACCEPTED = "OrderAccepted";
    public static final String CANCEL_REQUESTED = "CancelRequested";

    private EventTypes() {
    }
}
