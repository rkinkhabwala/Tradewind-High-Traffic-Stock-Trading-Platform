package com.tradewind.account.funding;

import com.tradewind.account.PostgresTestConfiguration;
import com.tradewind.account.user.AccountRepository;
import com.tradewind.account.user.AccountStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, FundingTest.FakeLedgerConfig.class})
class FundingTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeLedgerConfig {
        @Bean
        @Primary
        FakePortfolioLedger fakePortfolioLedger() {
            return new FakePortfolioLedger();
        }
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    FakePortfolioLedger ledger;
    @Autowired
    AccountRepository accounts;

    @AfterEach
    void healLedger() {
        ledger.unavailable = false;
        ledger.delay = Duration.ZERO;
    }

    private UUID newAccount() {
        Map<?, ?> body = http.postForObject("/api/auth/register",
                Map.of("email", "funding-" + UUID.randomUUID() + "@example.com", "password", "correct-horse"), Map.class);
        return UUID.fromString((String) body.get("accountId"));
    }

    private ResponseEntity<Map> post(String path, UUID accountId, String key, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accountId != null) {
            headers.set("X-Account-Id", accountId.toString());
        }
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.postForEntity(path, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> deposit(UUID accountId, String key, long cents) {
        return post("/api/account/deposits", accountId, key, Map.of("amountCents", cents));
    }

    private ResponseEntity<Map> withdraw(UUID accountId, String key, long cents) {
        return post("/api/account/withdrawals", accountId, key, Map.of("amountCents", cents));
    }

    @Test
    void depositIsCreditedAndReplayedForTheSameKey() {
        UUID account = newAccount();
        int callsBefore = ledger.calls.get();

        ResponseEntity<Map> first = deposit(account, "dep-1", 10_000_000);
        ResponseEntity<Map> replay = deposit(account, "dep-1", 10_000_000);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).containsEntry("type", "DEPOSIT")
                .containsEntry("amountCents", 10_000_000).containsEntry("cashBalanceCents", 10_000_000);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(ledger.calls.get() - callsBefore).isEqualTo(1);
    }

    @Test
    void fiveConcurrentRequestsWithOneKeyMoveMoneyOnce() throws Exception {
        UUID account = newAccount();
        ledger.delay = Duration.ofMillis(200);
        int callsBefore = ledger.calls.get();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return deposit(account, "same-key", 5_000);
                }));
            }
            start.countDown();
            Map<?, ?> firstBody = results.getFirst().get().getBody();
            for (Future<ResponseEntity<Map>> result : results) {
                assertThat(result.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(result.get().getBody()).isEqualTo(firstBody);
            }
        }
        assertThat(ledger.calls.get() - callsBefore).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        UUID account = newAccount();
        deposit(account, "dep-x", 1_000);

        ResponseEntity<Map> reused = deposit(account, "dep-x", 2_000);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void overdrawingWithdrawalIsRejectedAndTheRejectionIsReplayed() {
        UUID account = newAccount();
        deposit(account, "seed", 1_000);
        int callsBefore = ledger.calls.get();

        ResponseEntity<Map> rejected = withdraw(account, "wd-1", 1_001);
        ResponseEntity<Map> replay = withdraw(account, "wd-1", 1_001);
        ResponseEntity<Map> ok = withdraw(account, "wd-2", 1_000);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ok.getBody()).containsEntry("type", "WITHDRAWAL").containsEntry("cashBalanceCents", 0);
        assertThat(ledger.calls.get() - callsBefore).isEqualTo(2);
    }

    @Test
    void outageReturns503AndTheSameKeyCanBeRetried() {
        UUID account = newAccount();
        ledger.unavailable = true;

        ResponseEntity<Map> failed = deposit(account, "retry-me", 2_500);
        ledger.unavailable = false;
        ResponseEntity<Map> retried = deposit(account, "retry-me", 2_500);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failed.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retried.getBody()).containsEntry("cashBalanceCents", 2_500);
    }

    @Test
    void requestsWithoutKeyOrIdentityOrWithBadAmountsAreRejected() {
        UUID account = newAccount();

        assertThat(deposit(account, null, 100).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deposit(account, "bad key!", 100).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deposit(null, "k", 100).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(deposit(account, "neg", -1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deposit(account, "huge", 100_000_001).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/account/deposits", account, "frac", Map.of("amountCents", 1.5)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lockedAccountCannotMoveMoney() {
        UUID account = newAccount();
        var entity = accounts.findById(account).orElseThrow();
        entity.setStatus(AccountStatus.LOCKED);
        accounts.save(entity);

        assertThat(deposit(account, "locked", 100).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
