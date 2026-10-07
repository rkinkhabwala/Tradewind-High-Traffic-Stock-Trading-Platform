package com.tradewind.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Replaces any client-supplied identity headers with the ones proven by the JWT, so downstream services can
 * trust {@code X-User-Id} / {@code X-Account-Id} without re-validating the token.
 */
@Component
class IdentityHeadersFilter implements GlobalFilter, Ordered {

    static final String USER_ID = "X-User-Id";
    static final String ACCOUNT_ID = "X-Account-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> withIdentity(exchange, ((JwtAuthenticationToken) auth).getToken()))
                .switchIfEmpty(Mono.fromSupplier(() -> withIdentity(exchange, null)))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withIdentity(ServerWebExchange exchange, Jwt jwt) {
        return exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(ACCOUNT_ID);
            if (jwt != null) {
                headers.set(USER_ID, jwt.getSubject());
                headers.set(ACCOUNT_ID, jwt.getClaimAsString(SecurityConfig.ACCOUNT_ID_CLAIM));
            }
        })).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
