package com.tradewind.order.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.web.RequestIdPropagatingInterceptor;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * HTTP client for portfolio-service with every protection the hot path needs: connect/read timeouts, a bulkhead
 * (a slow portfolio-service cannot tie up more than N order-service threads) and a circuit breaker (when it is
 * failing, callers get an immediate 503 instead of waiting for timeouts).
 */
@Component
class RestReservationClient implements ReservationClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    RestReservationClient(RestClient.Builder builder, PortfolioClientProperties properties, ObjectMapper objectMapper,
                          MeterRegistry meters) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.url()).requestFactory(requestFactory)
                .requestInterceptor(new RequestIdPropagatingInterceptor()).build();
        this.objectMapper = objectMapper;

        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(properties.failureRateThresholdPercent())
                .slowCallDurationThreshold(properties.slowCallThreshold())
                .slowCallRateThreshold(80)
                .waitDurationInOpenState(properties.openStateWait())
                .permittedNumberOfCallsInHalfOpenState(3)
                // Business outcomes (insufficient funds) are successes from the breaker's point of view.
                .recordExceptions(ReservationUnavailableException.class)
                .build());
        BulkheadRegistry bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentCalls())
                .maxWaitDuration(java.time.Duration.ZERO)
                .build());
        this.circuitBreaker = breakers.circuitBreaker("portfolio");
        this.bulkhead = bulkheads.bulkhead("portfolio");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meters);
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public Outcome reserveCash(UUID orderId, UUID accountId, long cents) {
        return reserve(Map.of("orderId", orderId, "accountId", accountId, "cashCents", cents));
    }

    @Override
    public Outcome reserveShares(UUID orderId, UUID accountId, String symbol, Quantity quantity) {
        return reserve(Map.of("orderId", orderId, "accountId", accountId, "symbol", symbol,
                "quantity", quantity.toJson()));
    }

    @Override
    public void release(UUID orderId) {
        protect(() -> client.post().uri("/internal/reservations/{id}/release", orderId)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 200) {
                        return null;
                    }
                    throw new ReservationUnavailableException("release returned " + status, null);
                }));
    }

    private Outcome reserve(Map<String, Object> body) {
        return protect(() -> client.post().uri("/internal/reservations").body(body)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 200) {
                        return new Reserved();
                    }
                    if (status == 422) {
                        Map<?, ?> problem = objectMapper.readValue(response.getBody(), Map.class);
                        Object code = problem.get("code");
                        return new Insufficient(code != null ? code.toString() : "INSUFFICIENT_BALANCE");
                    }
                    if (status == 409) {
                        return new AlreadyReleased();
                    }
                    if (status >= 500) {
                        throw new ReservationUnavailableException("portfolio-service returned " + status, null);
                    }
                    throw new IllegalStateException("Unexpected reservation response " + status);
                }));
    }

    private <T> T protect(Supplier<T> call) {
        Supplier<T> withTimeoutsMapped = () -> {
            try {
                return call.get();
            } catch (ResourceAccessException e) {
                throw new ReservationUnavailableException("portfolio-service unreachable or timed out", e);
            }
        };
        try {
            return Bulkhead.decorateSupplier(bulkhead,
                    CircuitBreaker.decorateSupplier(circuitBreaker, withTimeoutsMapped)).get();
        } catch (CallNotPermittedException e) {
            throw new ReservationUnavailableException("portfolio-service circuit open", e);
        } catch (BulkheadFullException e) {
            throw new ReservationUnavailableException("too many concurrent reservation calls", e);
        }
    }
}
