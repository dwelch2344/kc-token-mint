package com.example.keycloak.mint.token;

import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class TypeConfigLoader {

    private static final String ATTR_SCHEMA_URI = "schema-uri";
    private static final String ATTR_SCHEMA_CONTENT_TYPE = "schema-content-type";
    private static final String ATTR_MAX_TTL_SECONDS = "max-ttl-seconds";
    private static final String ATTR_ALLOWED_AUDIENCES = "allowed-audiences";

    public Optional<TypeConfig> load(KeycloakSession session, RealmModel realm, String type) {
        String scopeName = "mint:" + type;
        Optional<ClientScopeModel> scopeOpt = realm.getClientScopesStream()
                .filter(s -> scopeName.equals(s.getName()))
                .findFirst();

        if (scopeOpt.isEmpty()) {
            return Optional.empty();
        }

        ClientScopeModel scope = scopeOpt.get();

        String schemaUriStr = scope.getAttribute(ATTR_SCHEMA_URI);
        if (schemaUriStr == null || schemaUriStr.isBlank()) {
            return Optional.empty();
        }

        String maxTtlStr = scope.getAttribute(ATTR_MAX_TTL_SECONDS);
        if (maxTtlStr == null || maxTtlStr.isBlank()) {
            return Optional.empty();
        }

        String allowedAudiencesStr = scope.getAttribute(ATTR_ALLOWED_AUDIENCES);
        if (allowedAudiencesStr == null || allowedAudiencesStr.isBlank()) {
            return Optional.empty();
        }

        URI schemaUri = URI.create(schemaUriStr.trim());
        Duration maxTtl = Duration.ofSeconds(Long.parseLong(maxTtlStr.trim()));
        Set<String> allowedAudiences = Arrays.stream(allowedAudiencesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        String contentTypeOverride = scope.getAttribute(ATTR_SCHEMA_CONTENT_TYPE);
        Optional<String> contentTypeOpt = (contentTypeOverride != null && !contentTypeOverride.isBlank())
                ? Optional.of(contentTypeOverride.trim())
                : Optional.empty();

        return Optional.of(new TypeConfig(type, schemaUri, contentTypeOpt, maxTtl, allowedAudiences));
    }
}
