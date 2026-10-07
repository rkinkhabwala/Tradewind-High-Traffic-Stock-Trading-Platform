package com.tradewind.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatewayRoutingSecurityTest {

    private static final String ISSUER = "tradewind-account";
    private static final RSAKey SIGNING_KEY = generateKey();
    private static final RSAKey FOREIGN_KEY = generateKey();
    private static final StubDownstream DOWNSTREAM = startDownstream();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ACCOUNT_SERVICE_URL", DOWNSTREAM::baseUrl);
        registry.add("MARKETDATA_SERVICE_URL", DOWNSTREAM::baseUrl);
        registry.add("ORDER_SERVICE_URL", DOWNSTREAM::baseUrl);
        registry.add("PORTFOLIO_SERVICE_URL", DOWNSTREAM::baseUrl);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop();
    }

    @Autowired
    WebTestClient web;

    @Test
    void rejectsMissingToken() {
        web.get().uri("/api/portfolio").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    void rejectsExpiredWrongIssuerAndForeignSignedTokens() {
        String user = UUID.randomUUID().toString();
        String expired = token(SIGNING_KEY, ISSUER, user, UUID.randomUUID().toString(),
                Instant.now().minus(1, ChronoUnit.HOURS));
        String wrongIssuer = token(SIGNING_KEY, "someone-else", user, UUID.randomUUID().toString(), null);
        String foreign = token(FOREIGN_KEY, ISSUER, user, UUID.randomUUID().toString(), null);
        String noAccount = token(SIGNING_KEY, ISSUER, user, null, null);

        for (String bad : new String[]{expired, wrongIssuer, foreign, noAccount}) {
            web.get().uri("/api/portfolio").headers(h -> h.setBearerAuth(bad)).exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void validTokenIsRoutedWithIdentityFromTheTokenNotTheClient() {
        String userId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();

        web.get().uri("/api/portfolio")
                .headers(h -> {
                    h.setBearerAuth(validToken(userId, accountId));
                    h.set("X-User-Id", "spoofed-user");
                    h.set("X-Account-Id", "spoofed-account");
                })
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/api/portfolio")
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.accountId").isEqualTo(accountId);
    }

    @Test
    void authRoutesArePublicAndSpoofedIdentityIsStripped() {
        web.post().uri("/api/auth/login")
                .header("X-User-Id", "spoofed-user")
                .header("X-Account-Id", "spoofed-account")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/api/auth/login")
                .jsonPath("$.userId").doesNotExist()
                .jsonPath("$.accountId").doesNotExist();
    }

    @Test
    void routesEachPrefixToItsService() {
        String token = validToken(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        for (String path : new String[]{"/api/account/deposits", "/api/quotes", "/api/symbols/AAPL", "/api/search",
                "/api/orders/123", "/api/activity"}) {
            web.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.path").isEqualTo(path);
        }
    }

    @Test
    void internalEndpointsAreNeverReachable() {
        String token = validToken(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        web.post().uri("/internal/reservations").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isForbidden();
        web.post().uri("/internal/reservations").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void requestIdIsPropagatedOrGenerated() {
        String token = validToken(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        web.get().uri("/api/portfolio").headers(h -> {
                    h.setBearerAuth(token);
                    h.set("X-Request-Id", "client-req-42");
                }).exchange()
                .expectHeader().valueEquals("X-Request-Id", "client-req-42")
                .expectBody().jsonPath("$.requestId").isEqualTo("client-req-42");

        web.get().uri("/api/portfolio").headers(h -> h.setBearerAuth(token)).exchange()
                .expectBody().jsonPath("$.requestId").value(id -> assertThat((String) id).hasSize(36));
    }

    @Test
    void placingOrdersIsRateLimitedPerUserWithRetryAfter() {
        String limitedUser = validToken(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 40; i++) {
            var result = web.post().uri("/api/orders").headers(h -> h.setBearerAuth(limitedUser))
                    .exchange().returnResult(String.class);
            HttpStatus status = HttpStatus.valueOf(result.getStatus().value());
            if (status == HttpStatus.OK) {
                ok++;
            } else {
                assertThat(status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(result.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
                limited++;
            }
        }
        // Burst of 20 plus whatever refilled (10/s) while the loop ran.
        assertThat(ok).isBetween(20, 35);
        assertThat(limited).isPositive();

        // Buckets are per user, and reads of orders are not limited.
        String otherUser = validToken(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        web.post().uri("/api/orders").headers(h -> h.setBearerAuth(otherUser)).exchange().expectStatus().isOk();
        web.get().uri("/api/orders").headers(h -> h.setBearerAuth(limitedUser)).exchange().expectStatus().isOk();
    }

    private static String validToken(String userId, String accountId) {
        return token(SIGNING_KEY, ISSUER, userId, accountId, null);
    }

    private static String token(RSAKey key, String issuer, String userId, String accountId, Instant expiresAt) {
        Instant now = Instant.now();
        Instant expiry = expiresAt != null ? expiresAt : now.plus(15, ChronoUnit.MINUTES);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId)
                .issueTime(Date.from(expiry.minus(15, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(expiry));
        if (accountId != null) {
            claims.claim("account_id", accountId);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                    .type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubDownstream startDownstream() {
        try {
            return new StubDownstream(SIGNING_KEY);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
