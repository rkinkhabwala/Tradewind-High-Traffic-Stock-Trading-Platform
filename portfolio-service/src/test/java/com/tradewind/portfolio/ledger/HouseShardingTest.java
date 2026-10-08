package com.tradewind.portfolio.ledger;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HouseShardingTest {

    @Test
    void shardZeroIsTheOriginalHouseAccount() {
        assertThat(LedgerAccountKey.HOUSE_ACCOUNT_ID).isEqualTo(new UUID(0L, 0L));
        assertThat(LedgerAccountKey.houseCash()).isEqualTo(
                new LedgerAccountKey(new UUID(0L, 0L), LedgerAccountType.HOUSE, null));
    }

    @Test
    void onlyHouseShardIdsMayOwnHouseAccounts() {
        assertThat(LedgerAccountKey.isHouseAccountId(new UUID(0L, LedgerAccountKey.HOUSE_SHARDS - 1))).isTrue();
        assertThat(LedgerAccountKey.isHouseAccountId(new UUID(0L, LedgerAccountKey.HOUSE_SHARDS))).isFalse();
        assertThat(LedgerAccountKey.isHouseAccountId(UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> new LedgerAccountKey(UUID.randomUUID(), LedgerAccountType.HOUSE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerAccountKey(new UUID(0L, 3L), LedgerAccountType.CASH, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postingsSpreadOverAllShardsAndTheChoiceIsStable() {
        Set<UUID> cashShards = new HashSet<>();
        Set<UUID> shareShards = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            UUID execution = UUID.randomUUID();
            LedgerAccountKey cash = LedgerAccountKey.houseCash(execution);
            assertThat(LedgerAccountKey.houseCash(execution)).isEqualTo(cash);     // replays hit the same row
            cashShards.add(cash.accountId());
            shareShards.add(LedgerAccountKey.houseShares("AAPL", execution).accountId());
            assertThat(cash.type()).isEqualTo(LedgerAccountType.HOUSE);
        }
        assertThat(cashShards).hasSize(LedgerAccountKey.HOUSE_SHARDS);
        assertThat(shareShards).hasSize(LedgerAccountKey.HOUSE_SHARDS);
    }
}
