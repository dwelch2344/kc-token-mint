package com.example.keycloak.mint.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TypeConfigLoaderTest {

    @Mock KeycloakSession session;
    @Mock RealmModel realm;
    @Mock ClientScopeModel scopeModel;

    private TypeConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new TypeConfigLoader();
    }

    @Test
    void returnsParsedConfigWhenScopeExists() {
        when(scopeModel.getName()).thenReturn("mint:capability");
        when(scopeModel.getAttribute("schema-uri")).thenReturn("https://example.com/schemas/cap.json");
        when(scopeModel.getAttribute("max-ttl-seconds")).thenReturn("3600");
        when(scopeModel.getAttribute("allowed-audiences")).thenReturn("svc-a, svc-b");
        when(scopeModel.getAttribute("schema-content-type")).thenReturn(null);
        when(realm.getClientScopesStream()).thenReturn(Stream.of(scopeModel));

        Optional<TypeConfig> result = loader.load(session, realm, "capability");

        assertTrue(result.isPresent());
        TypeConfig cfg = result.get();
        assertEquals("capability", cfg.type());
        assertEquals("https://example.com/schemas/cap.json", cfg.schemaUri().toString());
        assertEquals(3600, cfg.maxTtl().getSeconds());
        assertTrue(cfg.allowedAudiences().contains("svc-a"));
        assertTrue(cfg.allowedAudiences().contains("svc-b"));
        assertTrue(cfg.schemaContentTypeOverride().isEmpty());
    }

    @Test
    void returnsEmptyWhenNoMatchingScope() {
        when(scopeModel.getName()).thenReturn("openid");
        when(realm.getClientScopesStream()).thenReturn(Stream.of(scopeModel));

        Optional<TypeConfig> result = loader.load(session, realm, "capability");
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenSchemaUriMissing() {
        when(scopeModel.getName()).thenReturn("mint:capability");
        when(scopeModel.getAttribute("schema-uri")).thenReturn(null);
        when(realm.getClientScopesStream()).thenReturn(Stream.of(scopeModel));

        assertTrue(loader.load(session, realm, "capability").isEmpty());
    }

    @Test
    void returnsEmptyWhenMaxTtlMissing() {
        when(scopeModel.getName()).thenReturn("mint:capability");
        when(scopeModel.getAttribute("schema-uri")).thenReturn("https://example.com/s.json");
        when(scopeModel.getAttribute("max-ttl-seconds")).thenReturn(null);
        when(realm.getClientScopesStream()).thenReturn(Stream.of(scopeModel));

        assertTrue(loader.load(session, realm, "capability").isEmpty());
    }

    @Test
    void contentTypeOverrideIsPopulatedWhenSet() {
        when(scopeModel.getName()).thenReturn("mint:event");
        when(scopeModel.getAttribute("schema-uri")).thenReturn("https://example.com/s.json");
        when(scopeModel.getAttribute("max-ttl-seconds")).thenReturn("300");
        when(scopeModel.getAttribute("allowed-audiences")).thenReturn("downstream");
        when(scopeModel.getAttribute("schema-content-type")).thenReturn("application/schema+json");
        when(realm.getClientScopesStream()).thenReturn(Stream.of(scopeModel));

        Optional<TypeConfig> result = loader.load(session, realm, "event");
        assertTrue(result.isPresent());
        assertEquals(Optional.of("application/schema+json"), result.get().schemaContentTypeOverride());
    }
}
