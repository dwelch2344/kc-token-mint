package com.example.keycloak.mint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AuthenticationManager;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MintResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock BearerAuthenticator authenticator;
    @Mock MintEventLogger eventLogger;
    @Mock AuthenticationManager.AuthResult authResult;
    @Mock AccessToken callerToken;
    @Mock SchemaFetcher schemaFetcher;
    @Mock PayloadValidator validator;
    @Mock TypeConfigLoader typeConfigLoader;
    @Mock TokenBuilder tokenBuilder;

    private MintResource resource;
    private TypeConfig validTypeConfig;
    private FetchedSchema validSchema;

    @BeforeEach
    void setUp() throws Exception {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);

        resource = new MintResource(
                session, authenticator, eventLogger,
                schemaFetcher, List.of(validator),
                typeConfigLoader, tokenBuilder,
                4096, 8);

        validTypeConfig = new TypeConfig(
                "capability",
                URI.create("https://example.com/schemas/cap.json"),
                Optional.empty(),
                Duration.ofSeconds(3600),
                Set.of("svc-a"));

        validSchema = new FetchedSchema(
                MAPPER.readTree("{\"type\":\"object\"}"),
                "application/json",
                Optional.empty(), Optional.empty());
    }

    @Test
    void missingBearerReturns401() {
        when(authenticator.authenticate()).thenReturn(null);

        Response resp = resource.mint(request("capability", "{}", 300, "svc-a"));
        assertEquals(401, resp.getStatus());
    }

    @Test
    void missingTokenMinterRoleReturns403() {
        setupAuth(/* roles= */ Set.of("other-role"), "mint:capability");

        Response resp = resource.mint(request("capability", "{}", 300, "svc-a"));
        assertEquals(403, resp.getStatus());
    }

    @Test
    void missingMintScopeReturns403() {
        setupAuth(Set.of("token-minter"), "openid");

        Response resp = resource.mint(request("capability", "{}", 300, "svc-a"));
        assertEquals(403, resp.getStatus());
    }

    @Test
    void blankTypeReturns400() {
        setupAuth(Set.of("token-minter"), "openid mint:capability");
        MintRequest req = new MintRequest();
        req.setType("  ");
        req.setTtlSeconds(300);

        Response resp = resource.mint(req);
        assertEquals(400, resp.getStatus());
    }

    @Test
    void invalidTypeFormatReturns400() {
        setupAuth(Set.of("token-minter"), "mint:CAPS");

        Response resp = resource.mint(request("CAPS", "{}", 300, "svc-a"));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void reservedTypeReturns400() {
        setupAuth(Set.of("token-minter"), "openid mint:iss");

        Response resp = resource.mint(request("iss", "{}", 300, "svc-a"));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void ttlExceedsMaxReturns400() {
        setupAuth(Set.of("token-minter"), "openid mint:capability");
        when(typeConfigLoader.load(session, realm, "capability")).thenReturn(Optional.of(validTypeConfig));

        Response resp = resource.mint(request("capability", "{}", 9999, "svc-a"));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void disallowedAudienceReturns403() {
        setupAuth(Set.of("token-minter"), "openid mint:capability");
        when(typeConfigLoader.load(session, realm, "capability")).thenReturn(Optional.of(validTypeConfig));

        Response resp = resource.mint(request("capability", "{}", 300, "unknown-svc"));
        assertEquals(403, resp.getStatus());
    }

    @Test
    void schemaFetchFailureReturns503() {
        setupAuth(Set.of("token-minter"), "openid mint:capability");
        when(typeConfigLoader.load(session, realm, "capability")).thenReturn(Optional.of(validTypeConfig));
        when(schemaFetcher.fetch(any(), any())).thenThrow(new SchemaFetchException("down", true));

        Response resp = resource.mint(request("capability", "{\"resource\":\"x\",\"operations\":[]}", 300, "svc-a"));
        assertEquals(503, resp.getStatus());
    }

    @Test
    void payloadValidationFailureReturns422() throws Exception {
        setupAuth(Set.of("token-minter"), "openid mint:capability");
        when(typeConfigLoader.load(session, realm, "capability")).thenReturn(Optional.of(validTypeConfig));
        when(schemaFetcher.fetch(any(), any())).thenReturn(validSchema);
        when(validator.supports("application/json")).thenReturn(true);
        when(validator.validate(any(), any())).thenReturn(ValidationResult.fail("missing field"));

        Response resp = resource.mint(request("capability", "{}", 300, "svc-a"));
        assertEquals(422, resp.getStatus());
    }

    @Test
    void successReturns200WithToken() throws Exception {
        setupAuth(Set.of("token-minter"), "openid mint:capability");
        when(typeConfigLoader.load(session, realm, "capability")).thenReturn(Optional.of(validTypeConfig));
        when(schemaFetcher.fetch(any(), any())).thenReturn(validSchema);
        when(validator.supports("application/json")).thenReturn(true);
        when(validator.validate(any(), any())).thenReturn(ValidationResult.ok());
        when(tokenBuilder.build(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("header.eyJqdGkiOiJ0ZXN0LWp0aSJ9.sig");

        Response resp = resource.mint(request("capability", "{\"resource\":\"orders\",\"operations\":[]}", 300, "svc-a"));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void payloadTooLargeReturns400() {
        setupAuth(Set.of("token-minter"), "openid mint:capability");
        when(typeConfigLoader.load(session, realm, "capability")).thenReturn(Optional.of(validTypeConfig));
        String bigPayload = "{\"data\":\"" + "x".repeat(5000) + "\"}";

        Response resp = resource.mint(request("capability", bigPayload, 300, "svc-a"));
        assertEquals(400, resp.getStatus());
    }

    // ---- helpers ----

    private void setupAuth(Set<String> roles, String scope) {
        AccessToken.Access access = mock(AccessToken.Access.class);
        when(access.getRoles()).thenReturn(roles);
        when(authenticator.authenticate()).thenReturn(authResult);
        when(authResult.getToken()).thenReturn(callerToken);
        when(callerToken.getRealmAccess()).thenReturn(access);
        when(callerToken.getScope()).thenReturn(scope);
        lenient().when(callerToken.getIssuedFor()).thenReturn("test-client");
    }

    private MintRequest request(String type, String payloadJson, long ttl, String audience) {
        try {
            JsonNode payload = MAPPER.readTree(payloadJson);
            MintRequest req = new MintRequest();
            req.setType(type);
            req.setPayload(payload);
            req.setTtlSeconds(ttl);
            req.setAudience(audience);
            return req;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
