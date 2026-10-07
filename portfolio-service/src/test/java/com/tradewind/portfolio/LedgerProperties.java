package com.tradewind.portfolio;

import com.tradewind.common.execution.ExecutionReported;
import com.tradewind.common.money.Money;
import com.tradewind.common.money.Notional;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.portfolio.fill.FillService;
import com.tradewind.portfolio.funding.FundingService;
import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerAmounts;
import com.tradewind.portfolio.ledger.LedgerExceptions.InsufficientBalanceException;
import com.tradewind.portfolio.ledger.LedgerService;
import com.tradewind.portfolio.position.PositionRepository;
import com.tradewind.portfolio.reconciliation.ReconciliationService;
import com.tradewind.portfolio.reservation.ReservationService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 acceptance: "Property-based test (jqwik): random sequences of deposits/orders/cancels never produce
 * negative cash or unbalanced ledger txns." Random action sequences run against the real ledger services and
 * Postgres; an independent model predicts every balance to the cent. After each sequence the books must equal the
 * model, nothing may be negative, and reconciliation (every txn balanced per unit, cached == re-summed balances,
 * positions read model == ledger) must be clean.
 */
class LedgerProperties {

    enum Kind { DEPOSIT, WITHDRAW, BUY, SELL, FILL, CANCEL, REDELIVER }

    record Step(Kind kind, int pick, long amount, int fraction) {
    }

    static final List<String> SYMBOLS = List.of("AAA", "BBB");

    static PostgreSQLContainer<?> postgres;
    static GenericContainer<?> redis;
    static KafkaContainer kafka;
    static ConfigurableApplicationContext context;

    @BeforeContainer
    static void start() {
        postgres = new PostgreSQLContainer<>("postgres:16.15");
        redis = new GenericContainer<>("redis:7.4.11").withExposedPorts(6379);
        kafka = new KafkaContainer("apache/kafka:4.3.1");
        postgres.start();
        redis.start();
        kafka.start();
        context = new SpringApplicationBuilder(PortfolioServiceApplication.class)
                .web(WebApplicationType.NONE)
                // Command-line arguments outrank application.yml (builder "properties" would not).
                .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.data.redis.host=" + redis.getHost(),
                        "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());
    }

    @AfterContainer
    static void stop() {
        if (context != null) {
            context.close();
        }
        kafka.stop();
        redis.stop();
        postgres.stop();
    }

    @Provide
    Arbitrary<List<Step>> steps() {
        Arbitrary<Kind> kinds = Arbitraries.frequency(
                net.jqwik.api.Tuple.of(3, Kind.DEPOSIT), net.jqwik.api.Tuple.of(2, Kind.WITHDRAW),
                net.jqwik.api.Tuple.of(4, Kind.BUY), net.jqwik.api.Tuple.of(2, Kind.SELL),
                net.jqwik.api.Tuple.of(5, Kind.FILL), net.jqwik.api.Tuple.of(2, Kind.CANCEL),
                net.jqwik.api.Tuple.of(1, Kind.REDELIVER));
        return Combinators.combine(kinds, Arbitraries.integers().between(0, 1_000_000),
                        Arbitraries.longs().between(1, 3_000_000), Arbitraries.integers().between(1, 100))
                .as(Step::new).list().ofMinSize(1).ofMaxSize(40);
    }

    // ---- the independent model ---------------------------------------------------------------------------------

    static final class Account {
        final UUID id = UUID.randomUUID();
        long cash;
        long reserved;
        final Map<String, BigDecimal> owned = new HashMap<>();          // available + reserved shares
        final Map<String, BigDecimal> sharesReserved = new HashMap<>();
    }

    static final class Order {
        final UUID id = UUID.randomUUID();
        final Account account;
        final OrderSide side;
        final String symbol;
        final Price cap;                 // buys: most a fill may cost per share
        BigDecimal remaining;           // shares not yet filled
        BigDecimal reservedLeft;        // buys: dollars still reserved; sells: shares still reserved
        BigDecimal filledNotional = BigDecimal.ZERO;
        BigDecimal filled = BigDecimal.ZERO;
        int seq;

        Order(Account account, OrderSide side, String symbol, Price cap, BigDecimal qty, BigDecimal reserved) {
            this.account = account;
            this.side = side;
            this.symbol = symbol;
            this.cap = cap;
            this.remaining = qty;
            this.reservedLeft = reserved;
        }
    }

    @Property(tries = 500)
    void randomSequencesKeepTheBooksExactBalancedAndNonNegative(@ForAll("steps") List<Step> steps) {
        FundingService funding = context.getBean(FundingService.class);
        ReservationService reservations = context.getBean(ReservationService.class);
        FillService fills = context.getBean(FillService.class);

        List<Account> accounts = List.of(new Account(), new Account());
        List<Order> open = new ArrayList<>();
        Runnable lastFill = null;

        for (Step step : steps) {
            Account account = accounts.get(step.pick() % accounts.size());
            switch (step.kind()) {
                case DEPOSIT -> {
                    funding.deposit(UUID.randomUUID(), account.id, Money.ofCents(step.amount()));
                    account.cash += step.amount();
                }
                case WITHDRAW -> {
                    if (step.amount() <= account.cash) {
                        funding.withdraw(UUID.randomUUID(), account.id, Money.ofCents(step.amount()));
                        account.cash -= step.amount();
                    } else {
                        assertThatThrownBy(() -> funding.withdraw(UUID.randomUUID(), account.id, Money.ofCents(step.amount())))
                                .isInstanceOf(InsufficientBalanceException.class);
                    }
                }
                case BUY -> {
                    BigDecimal qty = BigDecimal.valueOf(1 + step.fraction() * 37L % 5_000, 2);     // 0.01-50.00 shares
                    Price cap = Price.ofCents(100 + step.pick() % 50_000);                           // $1.00-$501.00
                    Money reserve = Notional.buyReservation(Quantity.of(qty), cap, BigDecimal.ZERO);
                    Order order = new Order(account, OrderSide.BUY, SYMBOLS.get(step.pick() % 2), cap, qty,
                            LedgerAmounts.of(reserve));
                    if (reserve.cents() <= account.cash) {
                        reservations.reserve(order.id, account.id, LedgerAccountKey.USD, LedgerAmounts.of(reserve));
                        account.cash -= reserve.cents();
                        account.reserved += reserve.cents();
                        open.add(order);
                    } else {
                        assertThatThrownBy(() -> reservations.reserve(order.id, account.id, LedgerAccountKey.USD,
                                LedgerAmounts.of(reserve))).isInstanceOf(InsufficientBalanceException.class);
                    }
                }
                case SELL -> {
                    String symbol = SYMBOLS.get(step.pick() % 2);
                    BigDecimal available = account.owned.getOrDefault(symbol, BigDecimal.ZERO)
                            .subtract(account.sharesReserved.getOrDefault(symbol, BigDecimal.ZERO));
                    BigDecimal qty = available.multiply(BigDecimal.valueOf(step.fraction()))
                            .divide(BigDecimal.valueOf(100), 6, RoundingMode.DOWN);
                    if (qty.signum() > 0) {
                        Order order = new Order(account, OrderSide.SELL, symbol, null, qty, qty);
                        reservations.reserve(order.id, account.id, symbol, qty);
                        account.sharesReserved.merge(symbol, qty, BigDecimal::add);
                        open.add(order);
                    } else {
                        assertThatThrownBy(() -> reservations.reserve(UUID.randomUUID(), account.id, symbol,
                                new BigDecimal("1"))).isInstanceOf(InsufficientBalanceException.class);
                    }
                }
                case FILL -> {
                    if (open.isEmpty()) {
                        continue;
                    }
                    Order order = open.get(step.pick() % open.size());
                    BigDecimal qty = order.remaining.multiply(BigDecimal.valueOf(step.fraction()))
                            .divide(BigDecimal.valueOf(100), 6, RoundingMode.UP).min(order.remaining);
                    Price price = order.side == OrderSide.BUY
                            ? Price.ofCents(Math.max(1, order.cap.value().movePointRight(2).longValue() - step.amount() % 100))
                            : Price.ofCents(100 + step.amount() % 50_000);
                    BigDecimal after = order.filledNotional.add(qty.multiply(price.value()));
                    Money amount = Notional.fillIncrement(order.filledNotional, after);
                    order.filledNotional = after;
                    order.filled = order.filled.add(qty);
                    order.remaining = order.remaining.subtract(qty);
                    order.seq++;
                    boolean terminal = order.remaining.signum() == 0;
                    ExecutionReported event = new ExecutionReported(UUID.randomUUID(), order.id, order.account.id,
                            order.symbol, order.side, order.seq, Quantity.of(qty), price, Quantity.of(order.filled),
                            Quantity.of(order.remaining), terminal, Instant.now());
                    UUID eventId = UUID.randomUUID();
                    fills.onExecution(eventId, event);
                    lastFill = () -> fills.onExecution(eventId, event);

                    Account a = order.account;
                    if (order.side == OrderSide.BUY) {
                        a.reserved -= amount.cents();
                        order.reservedLeft = order.reservedLeft.subtract(LedgerAmounts.of(amount));
                        a.owned.merge(order.symbol, qty, BigDecimal::add);
                    } else {
                        a.cash += amount.cents();
                        a.owned.merge(order.symbol, qty.negate(), BigDecimal::add);
                        a.sharesReserved.merge(order.symbol, qty.negate(), BigDecimal::add);
                        order.reservedLeft = order.reservedLeft.subtract(qty);
                    }
                    if (terminal) {
                        release(order);
                        open.remove(order);
                    }
                }
                case CANCEL -> {
                    if (open.isEmpty()) {
                        continue;
                    }
                    Order order = open.remove(step.pick() % open.size());
                    fills.onOrderClosed(UUID.randomUUID(), order.id);
                    release(order);
                }
                case REDELIVER -> {
                    if (lastFill != null) {
                        lastFill.run();     // same event id again: must change nothing
                    }
                }
            }
            assertThat(account.cash).isNotNegative();
            assertThat(account.reserved).isNotNegative();
        }

        for (Account account : accounts) {
            assertBooksMatch(account);
        }
        ReconciliationService.Report report = context.getBean(ReconciliationService.class).run();
        assertThat(report.ok()).as(report.toString()).isTrue();
    }

    /** Model side of a release: whatever the order still holds goes back to available. */
    private static void release(Order order) {
        Account a = order.account;
        if (order.side == OrderSide.BUY) {
            long left = LedgerAmounts.toMoney(order.reservedLeft).cents();
            a.reserved -= left;
            a.cash += left;
        } else {
            a.sharesReserved.merge(order.symbol, order.reservedLeft.negate(), BigDecimal::add);
        }
        order.reservedLeft = BigDecimal.ZERO;
    }

    private static void assertBooksMatch(Account account) {
        LedgerService ledger = context.getBean(LedgerService.class);
        PositionRepository positions = context.getBean(PositionRepository.class);
        TransactionTemplate tx = context.getBean(TransactionTemplate.class);
        tx.executeWithoutResult(status -> {
            assertThat(LedgerAmounts.toMoney(ledger.balanceOf(LedgerAccountKey.cash(account.id))).cents())
                    .as("cash").isEqualTo(account.cash);
            assertThat(LedgerAmounts.toMoney(ledger.balanceOf(LedgerAccountKey.cashReserved(account.id))).cents())
                    .as("reserved cash").isEqualTo(account.reserved);
            for (String symbol : SYMBOLS) {
                BigDecimal owned = account.owned.getOrDefault(symbol, BigDecimal.ZERO);
                BigDecimal readModel = positions.find(account.id, symbol).map(p -> p.qty().value()).orElse(BigDecimal.ZERO);
                assertThat(readModel).as("position " + symbol).isEqualByComparingTo(owned);
                assertThat(ledger.balanceOf(LedgerAccountKey.positionReserved(account.id, symbol)))
                        .as("reserved " + symbol)
                        .isEqualByComparingTo(account.sharesReserved.getOrDefault(symbol, BigDecimal.ZERO));
            }
        });
    }
}
