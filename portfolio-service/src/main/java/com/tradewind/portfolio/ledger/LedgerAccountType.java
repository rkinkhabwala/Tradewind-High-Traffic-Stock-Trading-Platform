package com.tradewind.portfolio.ledger;

public enum LedgerAccountType {
    CASH,
    CASH_RESERVED,
    POSITION,
    POSITION_RESERVED,
    /** The simulated counterparty (bank for deposits, market for fills). Its balances may go negative. */
    HOUSE
}
