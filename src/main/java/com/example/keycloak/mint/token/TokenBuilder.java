package com.example.keycloak.mint.token;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.common.util.Time;
import org.keycloak.crypto.Algorithm;
import org.keycloak.crypto.AsymmetricSignatureSignerContext;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.jose.jws.JWSBuilder;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.Urls;

import java.time.Duration;
import java.util.UUID;

public class TokenBuilder {

    private static final String[] PREFERRED_ALGORITHMS = {
            Algorithm.RS256,
            Algorithm.ES256,
            Algorithm.PS256
    };

    public String build(
            RealmModel realm,
            KeycloakSession session,
            String callerClientId,
            String type,
            JsonNode payload,
            String audience,
            Duration ttl) {

        KeyWrapper signingKey = resolveActiveKey(session, realm);
        if (signingKey == null) {
            throw new IllegalStateException("No active signing key available for realm: " + realm.getName());
        }

        String issuer = Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName());
        long now = Time.currentTime();
        String jti = UUID.randomUUID().toString();

        JsonWebToken jwt = new JsonWebToken();
        jwt.id(jti);
        jwt.issuer(issuer);
        jwt.iat(now);
        jwt.exp(now + ttl.getSeconds());
        jwt.audience(audience);
        jwt.setOtherClaims("azp", callerClientId);
        jwt.setOtherClaims("type", type);
        jwt.setOtherClaims(type, payload);

        return new JWSBuilder()
                .kid(signingKey.getKid())
                .type("JWT")
                .jsonContent(jwt)
                .sign(new AsymmetricSignatureSignerContext(signingKey));
    }

    private KeyWrapper resolveActiveKey(KeycloakSession session, RealmModel realm) {
        for (String algorithm : PREFERRED_ALGORITHMS) {
            KeyWrapper key = session.keys().getActiveKey(realm, KeyUse.SIG, algorithm);
            if (key != null) return key;
        }
        return null;
    }
}
