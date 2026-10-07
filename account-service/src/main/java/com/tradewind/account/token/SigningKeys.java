package com.tradewind.account.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Loads the RS256 signing key from a PKCS#8 PEM, or generates an ephemeral one for local development. */
public final class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private SigningKeys() {
    }

    public static RSAKey load(String pkcs8Pem) {
        try {
            if (pkcs8Pem == null || pkcs8Pem.isBlank()) {
                log.warn("JWT_PRIVATE_KEY not set: generating an ephemeral signing key. "
                        + "Access tokens become invalid when this instance restarts.");
                return new RSAKeyGenerator(2048)
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .keyIDFromThumbprint(true)
                        .generate();
            }
            return fromPem(pkcs8Pem);
        } catch (JOSEException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load JWT signing key", e);
        }
    }

    private static RSAKey fromPem(String pem) throws GeneralSecurityException, JOSEException {
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint()
                .build();
    }
}
