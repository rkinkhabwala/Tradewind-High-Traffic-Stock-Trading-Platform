package com.tradewind.stream.ws;

import com.tradewind.stream.StreamProperties;
import com.tradewind.stream.quote.QuoteMessages;
import com.tradewind.stream.quote.QuoteSnapshots;
import com.tradewind.stream.session.ClientSession;
import com.tradewind.stream.session.SessionRegistry;
import com.tradewind.stream.session.StreamMetrics;
import com.tradewind.stream.session.UserChannels;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** One WebSocket connection: parses client ops and wires the session's outbound stream to the socket. */
@Component
class StreamWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamWebSocketHandler.class);
    static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9.]{0,9}");

    private final SessionRegistry registry;
    private final UserChannels userChannels;
    private final QuoteSnapshots quotes;
    private final StreamProperties properties;
    private final Scheduler scheduler;
    private final StreamMetrics metrics;

    StreamWebSocketHandler(SessionRegistry registry, UserChannels userChannels, QuoteSnapshots quotes, StreamProperties properties,
                           @Qualifier("streamScheduler") Scheduler scheduler, MeterRegistry meters) {
        this.registry = registry;
        this.userChannels = userChannels;
        this.quotes = quotes;
        this.properties = properties;
        this.scheduler = scheduler;
        this.metrics = new StreamMetrics(meters);
    }

    @Override
    public Mono<Void> handle(WebSocketSession socket) {
        HttpHeaders headers = socket.getHandshakeInfo().getHeaders();
        Sinks.Empty<Void> kill = Sinks.empty();
        ClientSession session = new ClientSession(socket.getId(),
                IdentityFilter.parse(headers.getFirst(IdentityFilter.USER_ID)),
                IdentityFilter.parse(headers.getFirst(IdentityFilter.ACCOUNT_ID)),
                properties, scheduler, (code, reason) -> close(socket, kill, code, reason), metrics);
        registry.register(session);
        userChannels.join(session);
        session.send(Protocol.hello(properties.instanceId(), properties.heartbeatInterval().toSeconds()));

        Mono<Void> out = socket.send(session.outbound().map(socket::textMessage));
        Mono<Void> in = socket.receive()
                .doOnNext(message -> onMessage(session, message))
                .then();
        return Mono.firstWithSignal(in, out, kill.asMono())
                .onErrorResume(e -> Mono.empty())
                .doFinally(signal -> {
                    userChannels.leave(session);
                    registry.unregister(session);
                });
    }

    private void onMessage(ClientSession session, WebSocketMessage message) {
        session.onInbound();
        if (message.getType() != WebSocketMessage.Type.TEXT) {
            return;
        }
        Protocol.ClientOp op = Protocol.read(message.getPayloadAsText());
        if (op == null || op.op() == null) {
            session.send(Protocol.error("BAD_REQUEST", "expected {\"op\": ...}"));
            return;
        }
        switch (op.op()) {
            case "ping" -> session.send(Protocol.pong());
            case "subscribe" -> subscribe(session, op.symbols());
            case "unsubscribe" -> {
                Set<String> symbols = normalize(session, op.symbols());
                if (symbols != null) {
                    registry.unsubscribe(session, symbols);
                    session.send(Protocol.subscriptions(session.symbols()));
                }
            }
            default -> session.send(Protocol.error("BAD_REQUEST", "unknown op: " + op.op()));
        }
    }

    private void subscribe(ClientSession session, List<String> requested) {
        Set<String> symbols = normalize(session, requested);
        if (symbols == null) {
            return;
        }
        quotes.knownSymbols().subscribe(known -> {
            if (!known.isEmpty()) {
                List<String> unknown = symbols.stream().filter(s -> !known.contains(s)).sorted().toList();
                if (!unknown.isEmpty()) {
                    session.send(Protocol.error("UNKNOWN_SYMBOL", "unknown symbols", unknown));
                    unknown.forEach(symbols::remove);
                }
            }
            List<String> added = registry.subscribe(session, symbols);
            if (added == null) {
                session.send(Protocol.error("TOO_MANY_SUBSCRIPTIONS",
                        "at most " + properties.maxSubscriptions() + " symbols per connection"));
                return;
            }
            session.send(Protocol.subscriptions(session.symbols()));
            quotes.latest(added).subscribe(q -> session.offerSnapshot(q.tick().symbol(), QuoteMessages.snapshot(q)));
        });
    }

    /** Upper-cased, de-duplicated symbols, or null (after sending an error) if any is malformed. */
    private Set<String> normalize(ClientSession session, List<String> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > properties.maxSubscriptions()) {
            session.send(Protocol.error("BAD_REQUEST", "symbols must list 1-" + properties.maxSubscriptions() + " symbols"));
            return null;
        }
        Set<String> symbols = new LinkedHashSet<>();
        for (String s : requested) {
            String symbol = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
            if (!SYMBOL.matcher(symbol).matches()) {
                session.send(Protocol.error("BAD_REQUEST", "invalid symbol", List.of(String.valueOf(s))));
                return null;
            }
            symbols.add(symbol);
        }
        return symbols;
    }

    /**
     * Queues a close frame; a client that never reads cannot receive it, so after a grace period the handler ends,
     * which drops the connection either way.
     */
    private void close(WebSocketSession socket, Sinks.Empty<Void> kill, int code, String reason) {
        log.debug("Closing session {}: {} {}", socket.getId(), code, reason);
        socket.close(new CloseStatus(code, reason)).subscribe(null, e -> { });
        scheduler.schedule(kill::tryEmitEmpty, properties.closeGrace().toMillis(), TimeUnit.MILLISECONDS);
    }
}
