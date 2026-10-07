package com.tradewind.account.token;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeysTest {

    @Test
    void loadsPkcs8PemWithStableKeyId() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        RSAKey first = SigningKeys.load(pem);
        RSAKey second = SigningKeys.load(pem);

        assertThat(first.isPrivate()).isTrue();
        assertThat(first.toRSAPublicKey()).isEqualTo(pair.getPublic());
        assertThat(first.getKeyID()).isNotBlank().isEqualTo(second.getKeyID());
    }

    @Test
    void generatesEphemeralKeyWhenNotConfigured() {
        assertThat(SigningKeys.load("").isPrivate()).isTrue();
        assertThat(SigningKeys.load(null).getKeyID()).isNotBlank();
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> SigningKeys.load("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class);
    }
}
