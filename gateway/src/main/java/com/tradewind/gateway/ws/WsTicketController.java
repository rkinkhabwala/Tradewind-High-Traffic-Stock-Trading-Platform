package com.tradewind.gateway.ws;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** {@code POST /api/ws/ticket}: authenticated with the normal bearer JWT, served by the gateway itself. */
@RestController
class WsTicketController {

    record TicketResponse(String ticket, long expiresInSeconds) {
    }

    private final WsTickets tickets;

    WsTicketController(WsTickets tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/api/ws/ticket")
    Mono<TicketResponse> issue(@AuthenticationPrincipal Jwt jwt) {
        return tickets.issue(jwt.getSubject(), jwt.getClaimAsString("account_id"))
                .map(ticket -> new TicketResponse(ticket, tickets.ttl().toSeconds()));
    }
}
