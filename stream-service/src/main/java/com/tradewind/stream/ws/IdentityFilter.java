package com.tradewind.stream.ws;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The gateway authenticates the WebSocket ticket and injects the caller's identity (milestone k); stream-service is
 * not published outside the container network. A handshake without identity headers is refused.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityFilter implements WebFilter {

    static final String USER_ID = "X-User-Id";
    static final String ACCOUNT_ID = "X-Account-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (WebSocketConfig.PATH.equals(exchange.getRequest().getPath().value())
                && (parse(exchange, USER_ID) == null || parse(exchange, ACCOUNT_ID) == null)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    static UUID parse(ServerWebExchange exchange, String header) {
        return parse(exchange.getRequest().getHeaders().getFirst(header));
    }

    static UUID parse(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
