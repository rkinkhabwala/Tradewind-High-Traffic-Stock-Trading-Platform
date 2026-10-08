package com.tradewind.stream.session;

import com.tradewind.stream.StreamProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Conflation, backpressure and slow/idle handling on virtual time, without a socket. */
class ClientSessionTest {

    static final StreamProperties PROPS = new StreamProperties("test", Duration.ofMillis(250), 3, 4,
            Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(45), Duration.ofSeconds(5), 16384);

    /** Requests only what the test asks for, like a socket whose peer stopped reading. */
    static class Client extends BaseSubscriber<String> {
        final List<String> received = new CopyOnWriteArrayList<>();
        final long initial;

        Client(long initial) {
            this.initial = initial;
        }

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            if (initial > 0) {
                subscription.request(initial);
            }
        }

        @Override
        protected void hookOnNext(String value) {
            received.add(value);
        }
    }

    final List<Integer> closes = new CopyOnWriteArrayList<>();
    VirtualTimeScheduler time;
    StreamMetrics metrics;

    @BeforeEach
    void setUp() {
        time = VirtualTimeScheduler.create();
        metrics = StreamMetrics.noop();
    }

    @AfterEach
    void tearDown() {
        time.dispose();
    }

    ClientSession session(Client client, String... symbols) {
        ClientSession session = new ClientSession("s1", UUID.randomUUID(), UUID.randomUUID(), PROPS, time,
                (code, reason) -> closes.add(code), metrics);
        session.outbound().subscribe(client);
        assertThat(session.addSymbols(Set.of(symbols))).isNotNull();
        return session;
    }

    @Test
    void sendsAtMostOneQuotePerSymbolPerIntervalAndTheLatestWins() {
        Client client = new Client(Long.MAX_VALUE);
        ClientSession session = session(client, "AAPL", "TSLA");

        session.offerQuote("AAPL", "aapl-1");                    // a quiet symbol goes out at once
        assertThat(client.received).containsExactly("aapl-1");
        for (int i = 2; i <= 10; i++) {
            session.offerQuote("AAPL", "aapl-" + i);
        }
        session.offerQuote("TSLA", "tsla-1");
        time.advanceTimeBy(Duration.ofMillis(249));
        assertThat(client.received).hasSize(1);                   // still inside the interval
        time.advanceTimeBy(Duration.ofMillis(1));
        assertThat(client.received).containsExactly("aapl-1", "aapl-10", "tsla-1");

        session.offerQuote("AAPL", "aapl-11");
        session.offerQuote("AAPL", "aapl-12");
        time.advanceTimeBy(Duration.ofMillis(249));
        assertThat(client.received).hasSize(3);
        time.advanceTimeBy(Duration.ofMillis(1));
        assertThat(client.received).hasSize(4).last().isEqualTo("aapl-12");
    }

    @Test
    void aClientWithoutDemandGetsOnlyTheNewestQuoteAndControlMessagesFirst() {
        Client client = new Client(0);
        ClientSession session = session(client, "AAPL");

        session.offerQuote("AAPL", "old");
        time.advanceTime();
        time.advanceTimeBy(Duration.ofMillis(250));
        session.offerQuote("AAPL", "new");
        time.advanceTimeBy(Duration.ofMillis(250));
        session.send("pong");
        assertThat(client.received).isEmpty();

        client.request(10);
        assertThat(client.received).containsExactly("pong", "new");
        assertThat(metrics.staleDropped()).isEqualTo(1);
    }

    @Test
    void aClientThatStopsReadingIsClosedWith4000AfterTheTimeout() {
        Client client = new Client(0);
        ClientSession session = session(client, "AAPL");
        session.offerQuote("AAPL", "q");
        time.advanceTime();

        for (int s = 0; s < 10; s++) {
            time.advanceTimeBy(Duration.ofSeconds(1));
            session.onInbound();                                  // still pinging: slow, not idle
            session.check();
        }
        assertThat(closes).isEmpty();
        time.advanceTimeBy(Duration.ofMillis(100));
        session.check();
        session.check();

        assertThat(closes).containsExactly(ClientSession.CLOSE_SLOW);
        assertThat(metrics.closedSlow()).isEqualTo(1);
        session.offerQuote("AAPL", "after-close");                // ignored once closing
        client.request(10);
        assertThat(client.received).isEmpty();
    }

    @Test
    void aReadingClientWithoutTrafficIsNeverSlow() {
        Client client = new Client(Long.MAX_VALUE);
        ClientSession session = session(client, "AAPL");
        for (int s = 0; s < 60; s++) {
            time.advanceTimeBy(Duration.ofSeconds(1));
            if (s % 15 == 0) {
                session.onInbound();
            }
            session.check();
        }
        assertThat(closes).isEmpty();
    }

    @Test
    void controlQueueOverflowClosesInsteadOfDropping() {
        Client client = new Client(0);
        ClientSession session = session(client, "AAPL");
        for (int i = 0; i < PROPS.sendQueueLimit(); i++) {
            session.send("m" + i);
        }
        assertThat(closes).isEmpty();
        session.send("one too many");
        assertThat(closes).containsExactly(ClientSession.CLOSE_SLOW);
    }

    @Test
    void aSilentClientIsClosedWith4001() {
        Client client = new Client(Long.MAX_VALUE);
        ClientSession session = session(client, "AAPL");
        time.advanceTimeBy(Duration.ofSeconds(45));
        session.check();
        assertThat(closes).isEmpty();
        time.advanceTimeBy(Duration.ofMillis(1));
        session.check();
        assertThat(closes).containsExactly(ClientSession.CLOSE_IDLE);
    }

    @Test
    void theSnapshotIsSkippedWhenALiveQuoteAlreadyArrived() {
        Client client = new Client(Long.MAX_VALUE);
        ClientSession session = session(client, "AAPL", "TSLA");
        session.offerQuote("AAPL", "live");
        time.advanceTime();
        session.offerSnapshot("AAPL", "snapshot-aapl");
        session.offerSnapshot("TSLA", "snapshot-tsla");
        session.offerSnapshot("MSFT", "not-subscribed");
        assertThat(client.received).containsExactly("live", "snapshot-tsla");
    }

    @Test
    void subscriptionsAreLimitedAndUnsubscribedQuotesIgnored() {
        Client client = new Client(Long.MAX_VALUE);
        ClientSession session = session(client, "A", "B");
        assertThat(session.addSymbols(Set.of("C", "D"))).isNull();         // limit 3
        assertThat(session.symbols()).containsExactly("A", "B");
        assertThat(session.addSymbols(Set.of("B", "C"))).containsExactly("C");
        assertThat(session.removeSymbols(Set.of("A", "Z"))).containsExactly("A");
        session.offerQuote("A", "a");
        session.offerQuote("C", "c");
        time.advanceTime();
        assertThat(client.received).containsExactly("c");
    }
}
