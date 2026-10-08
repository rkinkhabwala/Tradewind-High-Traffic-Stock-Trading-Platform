package com.tradewind.account.auth;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.tradewind.account.PostgresTestConfiguration;
import com.tradewind.account.user.AccountRepository;
import com.tradewind.account.user.AccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

    /** Access token from the body, refresh token from the cookie, as a browser would hold them. */
    record Session(String accessToken, String refreshCookie) {
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private ResponseEntity<Map> register(String email, String password) {
        return http.postForEntity("/api/auth/register", Map.of("email", email, "password", password), Map.class);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return http.postForEntity("/api/auth/login", Map.of("email", email, "password", password), Map.class);
    }

    private ResponseEntity<Map> postWithCookie(String path, String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        if (refreshToken != null) {
            headers.add(HttpHeaders.COOKIE, RefreshCookie.NAME + "=" + refreshToken);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> refresh(String refreshToken) {
        return postWithCookie("/api/auth/refresh", refreshToken);
    }

    private static String refreshCookieValue(ResponseEntity<?> response) {
        return setCookie(response).split(";")[0].substring(RefreshCookie.NAME.length() + 1);
    }

    private static String setCookie(ResponseEntity<?> response) {
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith(RefreshCookie.NAME + "=")).findFirst().orElseThrow();
    }

    private Session registerAndLogin(String email) {
        assertThat(register(email, "correct-horse").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> login = login(email, "correct-horse");
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Session((String) login.getBody().get("accessToken"), refreshCookieValue(login));
    }

    @Test
    void loginReturnsAccessTokenInBodyAndRefreshTokenOnlyInAHardenedCookie() {
        String email = uniqueEmail();
        assertThat(register(email, "correct-horse").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> login = login(email, "correct-horse");

        assertThat(login.getBody()).containsKeys("accessToken", "tokenType", "expiresIn")
                .doesNotContainKey("refreshToken");
        String cookie = setCookie(login);
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth")
                .contains("Secure").contains("Max-Age=604800");
        assertThat(refreshCookieValue(login)).hasSizeGreaterThan(40);
    }

    @Test
    void registeredUserGetsAccessTokenVerifiableWithPublishedJwks() throws Exception {
        String email = uniqueEmail();
        ResponseEntity<Map> registered = register(email, "correct-horse");
        String userId = (String) registered.getBody().get("userId");
        String accountId = (String) registered.getBody().get("accountId");

        Map<?, ?> body = login(email.toUpperCase(), "correct-horse").getBody();

        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        assertThat(body.get("expiresIn")).isEqualTo(900);
        String jwks = http.getForObject("/.well-known/jwks.json", String.class);
        RSAKey publicKey = JWKSet.parse(jwks).getKeys().getFirst().toRSAKey();
        assertThat(publicKey.isPrivate()).isFalse();
        SignedJWT jwt = SignedJWT.parse((String) body.get("accessToken"));
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("tradewind-account");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("account_id")).isEqualTo(accountId);
    }

    @Test
    void refreshRotatesTheCookieAndReuseRevokesTheWholeFamily() {
        Session first = registerAndLogin(uniqueEmail());

        ResponseEntity<Map> rotated = refresh(first.refreshCookie());
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated.getBody()).containsKey("accessToken").doesNotContainKey("refreshToken");
        String second = refreshCookieValue(rotated);
        assertThat(second).isNotEqualTo(first.refreshCookie());

        // Replaying the old cookie looks like theft: it fails and kills the newer token too.
        assertThat(refresh(first.refreshCookie()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshWithoutCookieIsUnauthorized() {
        assertThat(refresh(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void concurrentRefreshesWithSameCookieYieldExactlyOneNewToken() throws Exception {
        Session session = registerAndLogin(uniqueEmail());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<HttpStatus> call = () -> {
                    start.await();
                    return HttpStatus.valueOf(refresh(session.refreshCookie()).getStatusCode().value());
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
    void logoutRevokesTheCookieAndClearsItIdempotently() {
        Session session = registerAndLogin(uniqueEmail());

        ResponseEntity<Map> first = postWithCookie("/api/auth/logout", session.refreshCookie());
        ResponseEntity<Map> second = postWithCookie("/api/auth/logout", session.refreshCookie());
        ResponseEntity<Map> noCookie = postWithCookie("/api/auth/logout", null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setCookie(first)).contains("Max-Age=0").contains("Path=/api/auth");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(noCookie.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(session.refreshCookie()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        Session session = registerAndLogin(uniqueEmail());
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 second' where not revoked");

        assertThat(refresh(session.refreshCookie()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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

        ResponseEntity<Map> wrongPassword = login(email, "wrong-horse");
        ResponseEntity<Map> unknownEmail = login(uniqueEmail(), "correct-horse");

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("detail")).isEqualTo(unknownEmail.getBody().get("detail"));
        assertThat(wrongPassword.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
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
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "trace-me-123");
        var response = http.exchange("/.well-known/jwks.json", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-me-123");
    }
}
