package com.example.keycloak.mint.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.crypto.Algorithm;
import org.keycloak.crypto.KeyStatus;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.models.KeycloakUriInfo;
import org.keycloak.models.KeyManager;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock KeyManager keyManager;
    @Mock KeycloakUriInfo uriInfo;

    private TokenBuilder builder;
    private KeyWrapper keyWrapper;

    @BeforeEach
    void setUp() throws Exception {
        builder = new TokenBuilder();

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();

        keyWrapper = new KeyWrapper();
        keyWrapper.setKid("test-kid-1");
        keyWrapper.setAlgorithm(Algorithm.RS256);
        keyWrapper.setPrivateKey(keyPair.getPrivate());
        keyWrapper.setPublicKey(keyPair.getPublic());
        keyWrapper.setUse(KeyUse.SIG);
        keyWrapper.setStatus(KeyStatus.ACTIVE);
        keyWrapper.setType("RSA");

        // lenient: not all tests reach the issuer-URL construction
        lenient().when(session.getContext()).thenReturn(context);
        lenient().when(context.getUri()).thenReturn(uriInfo);
        lenient().when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:8080/"));
        lenient().when(realm.getName()).thenReturn("test-realm");
        lenient().when(session.keys()).thenReturn(keyManager);
        lenient().when(keyManager.getActiveKey(realm, KeyUse.SIG, Algorithm.RS256)).thenReturn(keyWrapper);
    }

    @Test
    void buildReturnsThreePartJwt() throws Exception {
        JsonNode payload = MAPPER.readTree("{\"resource\":\"orders\",\"operations\":[\"read\"]}");
        String jwt = builder.build(realm, session, "test-client", "capability",
                payload, "svc-a", Duration.ofSeconds(300));

        assertNotNull(jwt);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "JWT must have header.payload.signature");
    }

    @Test
    void jwtHeaderContainsKid() throws Exception {
        JsonNode payload = MAPPER.readTree("{\"resource\":\"orders\",\"operations\":[]}");
        String jwt = builder.build(realm, session, "test-client", "capability",
                payload, "svc-a", Duration.ofSeconds(300));

        String headerJson = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]));
        JsonNode header = MAPPER.readTree(headerJson);
        assertEquals("test-kid-1", header.get("kid").asText());
    }

    @Test
    void jwtClaimsContainExpectedFields() throws Exception {
        JsonNode payload = MAPPER.readTree("{\"resource\":\"orders\",\"operations\":[\"read\"]}");
        long before = System.currentTimeMillis() / 1000;
        String jwt = builder.build(realm, session, "mint-client", "capability",
                payload, "svc-a", Duration.ofSeconds(300));
        long after = System.currentTimeMillis() / 1000;

        String claimsJson = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        JsonNode claims = MAPPER.readTree(claimsJson);

        assertTrue(claims.has("iss"), "must have iss");
        assertTrue(claims.has("iat"), "must have iat");
        assertTrue(claims.has("exp"), "must have exp");
        assertTrue(claims.has("jti"), "must have jti");
        assertEquals("mint-client", claims.get("azp").asText());
        assertEquals("capability", claims.get("type").asText());
        assertTrue(claims.has("capability"), "must have discriminated payload claim");
        assertEquals("orders", claims.get("capability").get("resource").asText());

        long iat = claims.get("iat").asLong();
        long exp = claims.get("exp").asLong();
        assertTrue(iat >= before && iat <= after + 1);
        assertEquals(300, exp - iat, 1);
    }

    @Test
    void noActiveKeyThrows() throws Exception {
        when(keyManager.getActiveKey(realm, KeyUse.SIG, Algorithm.RS256)).thenReturn(null);
        when(keyManager.getActiveKey(realm, KeyUse.SIG, Algorithm.ES256)).thenReturn(null);
        when(keyManager.getActiveKey(realm, KeyUse.SIG, Algorithm.PS256)).thenReturn(null);

        JsonNode payload = MAPPER.readTree("{}");
        assertThrows(IllegalStateException.class, () ->
                builder.build(realm, session, "client", "capability",
                        payload, "svc-a", Duration.ofSeconds(300)));
    }
}
