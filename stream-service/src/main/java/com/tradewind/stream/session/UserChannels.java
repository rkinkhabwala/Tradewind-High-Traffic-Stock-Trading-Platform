package com.tradewind.stream.session;

import com.tradewind.common.userevent.UserEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays user events (order status, portfolio changes) from Redis channel {@code user:{accountId}} to that
 * account's sockets on this instance (spec 6.2). An instance subscribes to an account's channel only while it holds
 * at least one of the account's sockets, so Redis delivers each event just to the instances that need it.
 * Messages are already in the client format and go through the never-dropped queue of each session.
 * Best effort: an event published while no instance is subscribed is lost; clients refetch over REST on (re)connect.
 */
@Component
public class UserChannels {

    private static final Logger log = LoggerFactory.getLogger(UserChannels.class);

    private record Account(Set<ClientSession> sessions, Disposable subscription) {
    }

    private final ReactiveRedisMessageListenerContainer container;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Counter relayed;

    UserChannels(ReactiveRedisMessageListenerContainer container, MeterRegistry meters) {
        this.container = container;
        this.relayed = Counter.builder("stream.user.events.relayed").register(meters);
        Gauge.builder("stream.user.channels", accounts, Map::size).register(meters);
    }

    public void join(ClientSession session) {
        UUID accountId = session.accountId();
        accounts.compute(accountId, (id, account) -> {
            if (account == null) {
                account = new Account(ConcurrentHashMap.newKeySet(), subscribe(id));
            }
            account.sessions().add(session);
            return account;
        });
    }

    public void leave(ClientSession session) {
        accounts.computeIfPresent(session.accountId(), (id, account) -> {
            account.sessions().remove(session);
            if (!account.sessions().isEmpty()) {
                return account;
            }
            account.subscription().dispose();
            return null;
        });
    }

    /** Accounts this instance currently listens for. */
    public Set<UUID> accounts() {
        return Set.copyOf(accounts.keySet());
    }

    private Disposable subscribe(UUID accountId) {
        return container.receive(ChannelTopic.of(UserEventPublisher.channel(accountId)))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10))
                        .doBeforeRetry(s -> log.warn("User channel for {} failed, resubscribing: {}", accountId,
                                s.failure().toString())))
                .subscribe(message -> deliver(accountId, message.getMessage()));
    }

    private void deliver(UUID accountId, String json) {
        Account account = accounts.get(accountId);
        if (account != null) {
            account.sessions().forEach(session -> session.send(json));
            relayed.increment();
        }
    }
}
