package com.tradewind.order.idempotency;

import com.tradewind.order.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Orders, outbox and fills commit durably; only idempotency bookkeeping skips waiting for the WAL sync. */
@SpringBootTest
@Import(TestInfrastructure.class)
class DurabilitySplitTest {

    @Autowired
    JdbcClient main;
    @Autowired
    IdempotencyConfig.IdempotencyDatabase idempotency;

    @Test
    void onlyTheIdempotencyPoolRelaxesSynchronousCommit() {
        assertThat(main.sql("SHOW synchronous_commit").query(String.class).single()).isEqualTo("on");
        assertThat(idempotency.jdbc.sql("SHOW synchronous_commit").query(String.class).single()).isEqualTo("off");
        assertThat(idempotency.dataSource.getMaximumPoolSize()).isEqualTo(10);
    }
}
