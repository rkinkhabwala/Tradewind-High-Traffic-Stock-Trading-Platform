package com.tradewind.portfolio.ledger;

import com.tradewind.portfolio.ledger.LedgerRepository.AccountBalance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Internal view of an account's cached balances; the public portfolio endpoint comes with positions. */
@RestController
class BalancesController {

    record PositionBalance(String symbol, String quantity, String reservedQuantity) {
    }

    record AccountBalances(UUID accountId, long cashCents, long cashReservedCents, List<PositionBalance> positions) {
    }

    private final LedgerService ledger;

    BalancesController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/internal/accounts/{accountId}/balances")
    AccountBalances balances(@PathVariable UUID accountId) {
        long cash = 0;
        long cashReserved = 0;
        Map<String, BigDecimal[]> positions = new TreeMap<>();
        for (AccountBalance b : ledger.balancesOf(accountId)) {
            switch (b.type()) {
                case CASH -> cash = LedgerAmounts.toMoney(b.amount()).cents();
                case CASH_RESERVED -> cashReserved = LedgerAmounts.toMoney(b.amount()).cents();
                case POSITION -> positions.computeIfAbsent(b.symbol(), s -> zeros())[0] = b.amount();
                case POSITION_RESERVED -> positions.computeIfAbsent(b.symbol(), s -> zeros())[1] = b.amount();
                case HOUSE -> { }
            }
        }
        List<PositionBalance> positionList = positions.entrySet().stream()
                .map(e -> new PositionBalance(e.getKey(), e.getValue()[0].stripTrailingZeros().toPlainString(),
                        e.getValue()[1].stripTrailingZeros().toPlainString()))
                .toList();
        return new AccountBalances(accountId, cash, cashReserved, positionList);
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
    }
}
