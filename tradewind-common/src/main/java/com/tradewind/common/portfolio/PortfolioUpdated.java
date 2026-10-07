package com.tradewind.common.portfolio;

import com.tradewind.common.money.Money;
import com.tradewind.common.money.Quantity;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code PortfolioUpdated} v1 on {@code ledger.events}, keyed by account id (spec 6.3 step 7): the account's
 * balances right after a ledger change. {@code reason} is DEPOSIT, WITHDRAWAL, RESERVE, RELEASE or FILL;
 * {@code symbol}/{@code positionQty} are set when a position changed. Snapshot, not delta, so consumers can
 * apply the latest one and ignore older ones.
 */
public record PortfolioUpdated(UUID accountId, String reason, String referenceId, Money cash, Money cashReserved,
                               String symbol, Quantity positionQty, Instant occurredAt) {

    public static final int VERSION = 1;
}
