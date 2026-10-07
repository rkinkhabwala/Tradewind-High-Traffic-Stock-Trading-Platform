package com.tradewind.account.seed;

import com.tradewind.account.PostgresTestConfiguration;
import com.tradewind.account.funding.PortfolioLedgerClient;
import com.tradewind.account.token.TokenPair;
import com.tradewind.account.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, DemoUserSeederTest.RecordingLedger.class})
@TestPropertySource(properties = {"tradewind.seed.demo-users=false", "tradewind.seed.demo-password=demo-pass-123"})
class DemoUserSeederTest {

    /** Records deposits; fails the first call to prove the seeder retries until portfolio answers. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingLedger {
        @Bean
        @Primary
        Recorder recorder() {
            return new Recorder();
        }
    }

    static class Recorder implements PortfolioLedgerClient {
        final List<UUID[]> deposits = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger(1);

        @Override
        public LedgerResult deposit(UUID referenceId, UUID accountId, long amountCents) {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new PortfolioUnavailableException("not up yet", null);
            }
            deposits.add(new UUID[]{referenceId, accountId});
            return new LedgerResult(referenceId, amountCents);
        }

        @Override
        public LedgerResult withdraw(UUID referenceId, UUID accountId, long amountCents) {
            throw new UnsupportedOperationException();
        }
    }

    @Autowired
    DemoUserSeeder seeder;
    @Autowired
    Recorder ledger;
    @Autowired
    UserRepository users;
    @Autowired
    TestRestTemplate http;

    @Test
    void seedsTwoLoginableDemoUsersFundedOnceWithStableIdsAndReferences() {
        seeder.seed();
        seeder.seed();    // a restart: same users, same deposit references (portfolio dedupes them)

        assertThat(DemoUserSeeder.EMAILS).allSatisfy(email -> assertThat(users.findByEmail(email)).isPresent());
        assertThat(ledger.deposits).hasSize(4);
        assertThat(ledger.deposits.stream().map(d -> d[0]).distinct()).hasSize(2);
        assertThat(ledger.deposits.stream().map(d -> d[1]).distinct())
                .containsExactlyInAnyOrder(DemoUserSeeder.accountIdFor("demo1@tradewind.test"),
                        DemoUserSeeder.accountIdFor("demo2@tradewind.test"));

        var login = http.postForEntity("/api/auth/login",
                Map.of("email", "demo1@tradewind.test", "password", "demo-pass-123"), TokenPair.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
