package com.tradewind.portfolio.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionTest {

    private final UUID account = UUID.randomUUID();

    @Test
    void transferBalancesByConstruction() {
        LedgerTransaction txn = LedgerTransaction.transfer(LedgerTxnType.DEPOSIT, "ref",
                LedgerAccountKey.houseCash(), LedgerAccountKey.cash(account), new BigDecimal("100.00"));

        assertThat(txn.postings()).extracting(Posting::amount)
                .containsExactly(new BigDecimal("-100.000000"), new BigDecimal("100.000000"));
    }

    @Test
    void rejectsUnbalancedTransaction() {
        assertThatThrownBy(() -> new LedgerTransaction(LedgerTxnType.DEPOSIT, "ref", List.of(
                new Posting(LedgerAccountKey.houseCash(), new BigDecimal("-100.00")),
                new Posting(LedgerAccountKey.cash(account), new BigDecimal("99.99")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("USD");
    }

    @Test
    void dollarsAndSharesMustEachBalanceSeparately() {
        // Sums to zero overall, but 100 dollars out does not pay for 100 shares in.
        assertThatThrownBy(() -> new LedgerTransaction(LedgerTxnType.FILL, "ref", List.of(
                new Posting(LedgerAccountKey.cashReserved(account), new BigDecimal("-100")),
                new Posting(LedgerAccountKey.position(account, "AAPL"), new BigDecimal("100")))))
                .isInstanceOf(IllegalArgumentException.class);

        LedgerTransaction fill = new LedgerTransaction(LedgerTxnType.FILL, "ref", List.of(
                new Posting(LedgerAccountKey.cashReserved(account), new BigDecimal("-187.25")),
                new Posting(LedgerAccountKey.houseCash(), new BigDecimal("187.25")),
                new Posting(LedgerAccountKey.houseShares("AAPL"), new BigDecimal("-1")),
                new Posting(LedgerAccountKey.position(account, "AAPL"), new BigDecimal("1"))));
        assertThat(fill.postings()).hasSize(4);
    }

    @Test
    void rejectsZeroPostingsSubMicroAmountsAndWrongUnits() {
        assertThatThrownBy(() -> new Posting(LedgerAccountKey.cash(account), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Posting(LedgerAccountKey.cash(account), new BigDecimal("0.0000001")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new LedgerAccountKey(account, LedgerAccountType.CASH, "AAPL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerAccountKey(account, LedgerAccountType.HOUSE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
