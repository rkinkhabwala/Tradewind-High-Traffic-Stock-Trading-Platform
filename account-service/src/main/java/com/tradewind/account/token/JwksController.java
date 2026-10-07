package com.tradewind.account.token;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Not routed by the gateway; the gateway fetches it directly to validate tokens. */
@RestController
class JwksController {

    private final JwtIssuer jwtIssuer;

    JwksController(JwtIssuer jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return jwtIssuer.publicJwkSet();
    }
}
