package com.tradewind.gateway.ws;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Authenticates {@code /ws} by its one-time ticket: redeems it (single use), sets the same identity headers REST
 * requests get, and removes the ticket from the URL forwarded to stream-service. Runs right after
 * IdentityHeadersFilter has stripped any client-supplied identity headers. Query strings are never logged.
 */
@Component
class WsTicketFilter implements GlobalFilter, Ordered {

    static final String WS_PATH = "/ws";
    static final String USER_ID = "X-User-Id";
    static final String ACCOUNT_ID = "X-Account-Id";

    private final WsTickets tickets;

    WsTicketFilter(WsTickets tickets) {
        this.tickets = tickets;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!WS_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        String ticket = exchange.getRequest().getQueryParams().getFirst("ticket");
        if (ticket == null || ticket.isBlank()) {
            return unauthorized(exchange);
        }
        return tickets.redeem(ticket)
                .flatMap(identity -> {
                    URI withoutTicket = UriComponentsBuilder.fromUri(exchange.getRequest().getURI())
                            .replaceQueryParam("ticket").build(true).toUri();
                    ServerWebExchange authenticated = exchange.mutate().request(request -> request
                            .uri(withoutTicket)
                            .headers(headers -> {
                                headers.set(USER_ID, identity.userId());
                                headers.set(ACCOUNT_ID, identity.accountId());
                            })).build();
                    return chain.filter(authenticated);
                })
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange)));
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
