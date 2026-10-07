package com.tradewind.order.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.common.idempotency.IdempotencyKeys;
import com.tradewind.common.idempotency.StoredResponse;
import com.tradewind.order.order.OrderDtos.OrderPage;
import com.tradewind.order.order.OrderDtos.OrderView;
import com.tradewind.order.order.OrderDtos.PlaceOrderRequest;
import com.tradewind.order.web.Identity;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 200;

    private final OrderService orders;
    private final OrderRepository repository;
    private final ObjectMapper objectMapper;

    OrderController(OrderService orders, OrderRepository repository, ObjectMapper objectMapper) {
        this.orders = orders;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    ResponseEntity<String> place(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                                 @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String key,
                                 @Valid @RequestBody PlaceOrderRequest request) {
        UUID account = Identity.require(accountId);
        String validKey = IdempotencyKeys.requireValid(key);
        String hash = IdempotencyKeys.requestHash("POST", "/api/orders", canonical(request));
        return toResponse(orders.place(account, validKey, hash, request));
    }

    @DeleteMapping("/{orderId}")
    ResponseEntity<String> cancel(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                                  @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String key,
                                  @PathVariable UUID orderId) {
        UUID account = Identity.require(accountId);
        String validKey = IdempotencyKeys.requireValid(key);
        String hash = IdempotencyKeys.requestHash("DELETE", "/api/orders/" + orderId, new byte[0]);
        return toResponse(orders.cancel(account, validKey, hash, orderId));
    }

    @GetMapping("/{orderId}")
    ResponseEntity<OrderView> get(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                                  @PathVariable UUID orderId) {
        return orders.find(Identity.require(accountId), orderId)
                .map(o -> ResponseEntity.ok(OrderView.of(o)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Newest first. {@code cursor} is the opaque {@code nextCursor} of the previous page. */
    @GetMapping
    OrderPage list(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                   @RequestParam(required = false) OrderStatus status,
                   @RequestParam(required = false) String cursor,
                   @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        UUID account = Identity.require(accountId);
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be 1-" + MAX_PAGE);
        }
        List<Order> page = repository.page(account, status, decode(cursor), limit);
        String next = page.size() == limit ? encode(page.getLast()) : null;
        return new OrderPage(page.stream().map(OrderView::of).toList(), next);
    }

    private ResponseEntity<String> toResponse(StoredResponse response) {
        MediaType type = response.status() >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON;
        return ResponseEntity.status(response.status()).contentType(type).body(response.body());
    }

    private byte[] canonical(PlaceOrderRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String encode(Order last) {
        String raw = last.createdAt() + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static OrderRepository.Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            return new OrderRepository.Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }
}
