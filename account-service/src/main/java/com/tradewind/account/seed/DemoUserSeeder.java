package com.tradewind.account.seed;

import com.tradewind.account.funding.PortfolioLedgerClient;
import com.tradewind.account.funding.PortfolioLedgerClient.PortfolioUnavailableException;
import com.tradewind.account.user.AccountEntity;
import com.tradewind.account.user.AccountRepository;
import com.tradewind.account.user.AccountStatus;
import com.tradewind.account.user.UserEntity;
import com.tradewind.account.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1 seed (spec 9): two demo users with $100,000 each. The money goes in as a real ledger deposit, not a
 * balance insert, so reconciliation holds. Ids and the deposit reference are derived from the email, which makes
 * the whole seed idempotent: restarting the service never creates a second user or deposits twice. The deposit is
 * retried in the background until portfolio-service is up.
 */
@Component
public class DemoUserSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);
    public static final List<String> EMAILS = List.of("demo1@tradewind.test", "demo2@tradewind.test");
    public static final long STARTING_CASH_CENTS = 10_000_000;     // $100,000.00
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);
    static final int MAX_ATTEMPTS = 120;                          // ~10 minutes

    private final boolean enabled;
    private final String password;
    private final UserRepository users;
    private final AccountRepository accounts;
    private final PortfolioLedgerClient ledger;
    private final TransactionTemplate transactions;
    private final Clock clock;

    DemoUserSeeder(@Value("${tradewind.seed.demo-users:false}") boolean enabled,
                   @Value("${tradewind.seed.demo-password:}") String password, UserRepository users,
                   AccountRepository accounts, PortfolioLedgerClient ledger, TransactionTemplate transactions,
                   Clock clock) {
        this.enabled = enabled;
        this.password = password;
        this.users = users;
        this.accounts = accounts;
        this.ledger = ledger;
        this.transactions = transactions;
        this.clock = clock;
    }

    public static UUID userIdFor(String email) {
        return UUID.nameUUIDFromBytes(("demo-user:" + email).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID accountIdFor(String email) {
        return UUID.nameUUIDFromBytes(("demo-account:" + email).getBytes(StandardCharsets.UTF_8));
    }

    @EventListener(ApplicationReadyEvent.class)
    void seedInBackground() {
        if (!enabled) {
            return;
        }
        if (password == null || password.length() < 8) {
            log.warn("Demo users not seeded: DEMO_USER_PASSWORD must be at least 8 characters");
            return;
        }
        Thread.ofVirtual().name("demo-user-seeder").start(this::seed);
    }

    /** Creates the users (if missing) and funds them, retrying the deposit until portfolio-service answers. */
    public void seed() {
        String hash = new BCryptPasswordEncoder().encode(password);
        for (String email : EMAILS) {
            transactions.executeWithoutResult(status -> {
                if (users.findByEmail(email).isEmpty()) {
                    users.save(new UserEntity(userIdFor(email), email, hash, clock.instant()));
                    accounts.save(new AccountEntity(accountIdFor(email), userIdFor(email), AccountStatus.ACTIVE,
                            clock.instant()));
                }
            });
        }
        for (String email : EMAILS) {
            UUID account = accountIdFor(email);
            UUID reference = UUID.nameUUIDFromBytes(("demo-deposit:" + email).getBytes(StandardCharsets.UTF_8));
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    ledger.deposit(reference, account, STARTING_CASH_CENTS);
                    log.info("Demo user {} funded with $100,000", email);
                    break;
                } catch (PortfolioUnavailableException e) {
                    sleep();
                }
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
