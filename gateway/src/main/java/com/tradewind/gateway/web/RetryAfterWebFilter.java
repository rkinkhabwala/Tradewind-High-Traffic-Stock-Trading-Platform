package com.tradewind.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Spec 8: rate-limited requests get 429 with {@code Retry-After}. Spring Cloud Gateway's limiter sets the
 * status but not this header. One second is when the token bucket next refills.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RetryAfterWebFilter implements WebFilter {

    static final String RETRY_AFTER_SECONDS = "1";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            if (HttpStatus.TOO_MANY_REQUESTS.equals(response.getStatusCode())
                    && !response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)) {
                response.getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
