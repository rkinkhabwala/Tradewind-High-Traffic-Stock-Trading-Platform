package com.tradewind.account.watchlist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.account.web.Identity;
import com.tradewind.account.watchlist.WatchlistService.Watchlist;
import com.tradewind.account.watchlist.WatchlistService.WatchlistNotFoundException;
import com.tradewind.account.watchlist.WatchlistService.WatchlistRuleException;
import com.tradewind.common.idempotency.IdempotencyKeys;
import com.tradewind.common.idempotency.JdbcIdempotentExecutor;
import com.tradewind.common.idempotency.StoredResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Every mutating call needs an Idempotency-Key, like every other mutating endpoint (spec 4). */
@RestController
@RequestMapping("/api/watchlists")
class WatchlistController {

    private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9.]{0,9}");

    record CreateWatchlist(@NotBlank @Size(max = 50) String name) {
    }

    private final WatchlistService watchlists;
    private final JdbcIdempotentExecutor idempotency;
    private final ObjectMapper objectMapper;

    WatchlistController(WatchlistService watchlists, JdbcIdempotentExecutor idempotency, ObjectMapper objectMapper) {
        this.watchlists = watchlists;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    List<Watchlist> list(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId) {
        return watchlists.list(Identity.require(accountId));
    }

    @PostMapping
    ResponseEntity<String> create(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                                  @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String key,
                                  @Valid @RequestBody CreateWatchlist request) {
        String name = request.name().trim();
        return idempotent(accountId, key, "POST", "/api/watchlists", name, HttpStatus.CREATED,
                () -> watchlists.create(Identity.require(accountId), name));
    }

    @PutMapping("/{id}/symbols/{symbol}")
    ResponseEntity<String> add(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                               @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String key,
                               @PathVariable UUID id, @PathVariable String symbol) {
        String normalized = normalize(symbol);
        return idempotent(accountId, key, "PUT", "/api/watchlists/" + id + "/symbols/" + normalized, "", HttpStatus.OK,
                () -> watchlists.addSymbol(Identity.require(accountId), id, normalized));
    }

    @DeleteMapping("/{id}/symbols/{symbol}")
    ResponseEntity<String> remove(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                                  @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String key,
                                  @PathVariable UUID id, @PathVariable String symbol) {
        String normalized = normalize(symbol);
        return idempotent(accountId, key, "DELETE", "/api/watchlists/" + id + "/symbols/" + normalized, "", HttpStatus.OK,
                () -> watchlists.removeSymbol(Identity.require(accountId), id, normalized));
    }

    private ResponseEntity<String> idempotent(UUID accountId, String key, String method, String path, String body,
                                              HttpStatus success, Supplier<Watchlist> operation) {
        UUID account = Identity.require(accountId);
        String validKey = IdempotencyKeys.requireValid(key);
        String hash = IdempotencyKeys.requestHash(method, path, body.getBytes(StandardCharsets.UTF_8));
        StoredResponse response = idempotency.execute(account, validKey, hash, () -> {
            try {
                return json(success, operation.get());
            } catch (WatchlistRuleException e) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
                problem.setProperty("code", e.code());
                return json(HttpStatus.UNPROCESSABLE_ENTITY, problem);
            } catch (WatchlistNotFoundException e) {
                return json(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
            }
        });
        MediaType type = response.status() >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON;
        return ResponseEntity.status(response.status()).contentType(type).body(response.body());
    }

    private static String normalize(String symbol) {
        String upper = symbol.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL.matcher(upper).matches()) {
            throw new IllegalArgumentException("Invalid symbol: " + symbol);
        }
        return upper;
    }

    private StoredResponse json(HttpStatus status, Object body) {
        try {
            return new StoredResponse(status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
