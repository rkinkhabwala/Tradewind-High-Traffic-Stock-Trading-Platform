package com.tradewind.account.watchlist;

import com.tradewind.account.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class WatchlistTest {

    @Autowired
    TestRestTemplate http;

    private UUID account;

    @BeforeEach
    void newAccount() {
        Map<?, ?> registered = http.postForObject("/api/auth/register",
                Map.of("email", "w-" + UUID.randomUUID() + "@example.com", "password", "correct-horse"), Map.class);
        account = UUID.fromString((String) registered.get("accountId"));
    }

    private HttpHeaders headers(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Account-Id", account.toString());
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return headers;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String key, Object body) {
        return (ResponseEntity) http.exchange(path, method, new HttpEntity<>(body, headers(key)), Map.class);
    }

    private UUID create(String name, String key) {
        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, "/api/watchlists", key, Map.of("name", name));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    @Test
    void createAddRemoveAndList() {
        UUID id = create("Tech", "c1");

        call(HttpMethod.PUT, "/api/watchlists/" + id + "/symbols/aapl", "a1", null);
        call(HttpMethod.PUT, "/api/watchlists/" + id + "/symbols/TSLA", "a2", null);
        ResponseEntity<Map<String, Object>> again = call(HttpMethod.PUT, "/api/watchlists/" + id + "/symbols/AAPL", "a3", null);
        ResponseEntity<Map<String, Object>> removed = call(HttpMethod.DELETE, "/api/watchlists/" + id + "/symbols/TSLA", "d1", null);

        assertThat(again.getBody().get("symbols")).isEqualTo(List.of("AAPL", "TSLA"));
        assertThat(removed.getBody().get("symbols")).isEqualTo(List.of("AAPL"));
        List<?> lists = http.exchange("/api/watchlists", HttpMethod.GET, new HttpEntity<>(headers(null)), List.class).getBody();
        assertThat(lists).singleElement().satisfies(l -> assertThat(((Map<?, ?>) l).get("name")).isEqualTo("Tech"));
    }

    @Test
    void creationIsIdempotentPerKeyAndNamesAreUniquePerAccount() {
        UUID first = create("Growth", "same");
        UUID replay = create("Growth", "same");
        ResponseEntity<Map<String, Object>> duplicate = call(HttpMethod.POST, "/api/watchlists", "other", Map.of("name", "Growth"));

        assertThat(replay).isEqualTo(first);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(duplicate.getBody()).containsEntry("code", "NAME_TAKEN");
    }

    @Test
    void mutationsRequireKeyAndIdentityAndOnlyTouchOwnLists() {
        UUID id = create("Mine", "c1");

        assertThat(call(HttpMethod.POST, "/api/watchlists", null, Map.of("name", "x")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/watchlists", HttpMethod.GET, HttpEntity.EMPTY, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.PUT, "/api/watchlists/" + id + "/symbols/not!valid", "bad", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        account = UUID.randomUUID();   // someone else
        assertThat(call(HttpMethod.PUT, "/api/watchlists/" + id + "/symbols/AAPL", "steal", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
