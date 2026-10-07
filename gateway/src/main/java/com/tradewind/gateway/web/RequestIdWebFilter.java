package com.tradewind.gateway.web;

import com.tradewind.common.web.RequestIds;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Assigns {@code X-Request-Id} before security runs, so even 401/429 responses carry one. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = RequestIds.acceptOrGenerate(exchange.getRequest().getHeaders().getFirst(RequestIds.HEADER));
        exchange.getResponse().getHeaders().set(RequestIds.HEADER, requestId);
        ServerWebExchange withId = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(RequestIds.HEADER, requestId)))
                .build();
        return chain.filter(withId);
    }
}
