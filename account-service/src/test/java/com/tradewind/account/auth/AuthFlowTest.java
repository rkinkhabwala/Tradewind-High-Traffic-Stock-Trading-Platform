package com.tradewind.account.auth;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.tradewind.account.PostgresTestConfiguration;
import com.tradewind.account.token.TokenPair;
import com.tradewind.account.user.AccountRepository;
import com.tradewind.account.user.AccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class AuthFlowTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    AccountRepository accounts;
    @Autowired
    JdbcTemplate jdbc;

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private ResponseEntity<Map> register(String email, String password) {
        return http.postForEntity("/api/auth/register", Map.of("email", email, "password", password), Map.class);
    }

    private ResponseEntity<TokenPair> login(String email, String password) {
        return http.postForEntity("/api/auth/login", Map.of("email", email, "password", password), TokenPair.class);
    }

    private ResponseEntity<TokenPair> refresh(String refreshToken) {
        return http.postForEntity("/api/auth/refresh", Map.of("refreshToken", refreshToken), TokenPair.class);
    }

    private TokenPair registerAndLogin(String email) {
        assertThat(register(email, "correct-horse").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<TokenPair> login = login(email, "correct-horse");
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return login.getBody();
    }

    @Test
    void registeredUserGetsAccessTokenVerifiableWithPublishedJwks() throws Exception {
        String email = uniqueEmail();
        ResponseEntity<Map> registered = register(email, "correct-horse");
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String userId = (String) registered.getBody().get("userId");
        String accountId = (String) registered.getBody().get("accountId");

        TokenPair tokens = login(email.toUpperCase(), "correct-horse").getBody();

        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isEqualTo(900);
        String jwks = http.getForObject("/.well-known/jwks.json", String.class);
        RSAKey publicKey = JWKSet.parse(jwks).getKeys().getFirst().toRSAKey();
        assertThat(publicKey.isPrivate()).isFalse();
        SignedJWT jwt = SignedJWT.parse(tokens.accessToken());
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(publicKey.getKeyID());
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("tradewind-account");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("account_id")).isEqualTo(accountId);
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeFamily() {
        TokenPair first = registerAndLogin(uniqueEmail());

        ResponseEntity<TokenPair> rotated = refresh(first.refreshToken());
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated.getBody().refreshToken()).isNotEqualTo(first.refreshToken());

        // Replaying the old token looks like theft: it fails and kills the newer token too.
        assertThat(refresh(first.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(rotated.getBody().refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void concurrentRefreshesWithSameTokenYieldExactlyOneNewPair() throws Exception {
        TokenPair tokens = registerAndLogin(uniqueEmail());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<HttpStatus> call = () -> {
                    start.await();
                    return HttpStatus.valueOf(refresh(tokens.refreshToken()).getStatusCode().value());
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            long ok = 0;
            for (Future<HttpStatus> result : results) {
                if (result.get() == HttpStatus.OK) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(1);
        }
    }

    @Test
    void logoutRevokesRefreshTokenAndIsIdempotent() {
        TokenPair tokens = registerAndLogin(uniqueEmail());
        Map<String, String> body = Map.of("refreshToken", tokens.refreshToken());

        assertThat(http.postForEntity("/api/auth/logout", body, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.postForEntity("/api/auth/logout", body, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(tokens.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        TokenPair tokens = registerAndLogin(uniqueEmail());
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 second' where not revoked");

        assertThat(refresh(tokens.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void duplicateEmailIsCaseInsensitiveConflict() {
        String email = uniqueEmail();
        assertThat(register(email, "correct-horse").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> duplicate = register(email.toUpperCase(), "another-password");

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() {
        String email = uniqueEmail();
        register(email, "correct-horse");

        ResponseEntity<Map> wrongPassword = http.postForEntity("/api/auth/login",
                Map.of("email", email, "password", "wrong-horse"), Map.class);
        ResponseEntity<Map> unknownEmail = http.postForEntity("/api/auth/login",
                Map.of("email", uniqueEmail(), "password", "correct-horse"), Map.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("detail")).isEqualTo(unknownEmail.getBody().get("detail"));
    }

    @Test
    void lockedAccountCannotLogIn() {
        String email = uniqueEmail();
        UUID accountId = UUID.fromString((String) register(email, "correct-horse").getBody().get("accountId"));
        var account = accounts.findById(accountId).orElseThrow();
        account.setStatus(AccountStatus.LOCKED);
        accounts.save(account);

        assertThat(login(email, "correct-horse").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidInputIsRfc7807BadRequest() {
        ResponseEntity<Map> badEmail = register("not-an-email", "correct-horse");
        ResponseEntity<Map> shortPassword = register(uniqueEmail(), "short");

        assertThat(badEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badEmail.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(shortPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requestIdIsEchoed() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Request-Id", "trace-me-123");
        var response = http.exchange("/.well-known/jwks.json", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-me-123");
    }
}
