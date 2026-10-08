package com.tradewind.common.userevent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class UserEventPublisherTest {

    final List<String> sent = new CopyOnWriteArrayList<>();
    final UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void insideATransactionNothingIsSentUntilCommit() throws Exception {
        try (UserEventPublisher publisher = new UserEventPublisher((channel, message) -> sent.add(channel + " " + message))) {
            TransactionSynchronizationManager.initSynchronization();
            publisher.publishAfterCommit(account, Map.of("type", "order"));
            Thread.sleep(100);
            assertThat(sent).isEmpty();

            TransactionSynchronizationUtils.triggerAfterCommit();
            await().atMost(Duration.ofSeconds(2)).until(() -> sent.size() == 1);
            assertThat(sent).containsExactly("user:" + account + " {\"type\":\"order\"}");
        }
    }

    @Test
    void aRolledBackTransactionPublishesNothing() throws Exception {
        try (UserEventPublisher publisher = new UserEventPublisher((channel, message) -> sent.add(message))) {
            TransactionSynchronizationManager.initSynchronization();
            publisher.publishAfterCommit(account, Map.of("type", "order"));
            List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationUtils.invokeAfterCompletion(registered, TransactionSynchronization.STATUS_ROLLED_BACK);
            Thread.sleep(200);
            assertThat(sent).isEmpty();
        }
    }

    @Test
    void withoutATransactionItIsSentAtOnceInOrder() {
        try (UserEventPublisher publisher = new UserEventPublisher((channel, message) -> sent.add(message))) {
            for (int i = 0; i < 100; i++) {
                publisher.publishAfterCommit(account, Map.of("n", i));
            }
            await().atMost(Duration.ofSeconds(2)).until(() -> sent.size() == 100);
            assertThat(sent.getFirst()).isEqualTo("{\"n\":0}");
            assertThat(sent.getLast()).isEqualTo("{\"n\":99}");
            assertThat(publisher.published()).isEqualTo(100);
        }
    }

    @Test
    void aBrokenOrStalledTransportNeverReachesTheCaller() throws Exception {
        CountDownLatch stall = new CountDownLatch(1);
        try (UserEventPublisher failing = new UserEventPublisher((c, m) -> {
            throw new IllegalStateException("redis down");
        })) {
            failing.publishAfterCommit(account, Map.of("a", 1));
            await().atMost(Duration.ofSeconds(2)).until(() -> failing.failed() == 1);
        }
        try (UserEventPublisher stalled = new UserEventPublisher((c, m) -> {
            try {
                stall.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 2)) {
            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                stalled.publishAfterCommit(account, Map.of("n", i));      // 1 in flight, 2 queued, 7 dropped
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            assertThat(stalled.dropped()).isEqualTo(7);
            stall.countDown();
        }
    }
}
