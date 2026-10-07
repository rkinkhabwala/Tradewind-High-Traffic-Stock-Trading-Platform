package com.tradewind.account.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/** Issues RS256 access tokens. {@code sub} is the user id; {@code account_id} is the trading account. */
@Component
public class JwtIssuer {

    public static final String ACCOUNT_ID_CLAIM = "account_id";

    private final RSAKey key;
    private final RSASSASigner signer;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtIssuer(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = SigningKeys.load(properties.privateKey());
        try {
            this.signer = new RSASSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot create JWT signer", e);
        }
    }

    public String issue(UUID userId, UUID accountId) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .claim(ACCOUNT_ID_CLAIM, accountId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.accessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign JWT", e);
        }
        return jwt.serialize();
    }

    /** Public keys only, for the gateway to verify signatures. */
    public Map<String, Object> publicJwkSet() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }
}
