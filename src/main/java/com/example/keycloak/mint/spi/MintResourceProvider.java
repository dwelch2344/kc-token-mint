package com.example.keycloak.mint.spi;

import com.example.keycloak.mint.audit.MintEventLogger;
import com.example.keycloak.mint.resource.BearerAuthenticator;
import com.example.keycloak.mint.resource.MintResource;
import com.example.keycloak.mint.schema.SchemaFetcher;
import com.example.keycloak.mint.token.TokenBuilder;
import com.example.keycloak.mint.token.TypeConfigLoader;
import com.example.keycloak.mint.validation.PayloadValidator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.resource.RealmResourceProvider;

import java.util.List;

public class MintResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;
    private final SchemaFetcher schemaFetcher;
    private final List<PayloadValidator> validators;
    private final TypeConfigLoader typeConfigLoader;
    private final TokenBuilder tokenBuilder;
    private final int maxPayloadBytes;
    private final int maxPayloadDepth;

    public MintResourceProvider(
            KeycloakSession session,
            SchemaFetcher schemaFetcher,
            List<PayloadValidator> validators,
            TypeConfigLoader typeConfigLoader,
            TokenBuilder tokenBuilder,
            int maxPayloadBytes,
            int maxPayloadDepth) {
        this.session = session;
        this.schemaFetcher = schemaFetcher;
        this.validators = validators;
        this.typeConfigLoader = typeConfigLoader;
        this.tokenBuilder = tokenBuilder;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxPayloadDepth = maxPayloadDepth;
    }

    @Override
    public Object getResource() {
        RealmModel realm = session.getContext().getRealm();
        BearerAuthenticator authenticator =
                new AppAuthManager.BearerTokenAuthenticator(session)::authenticate;
        MintEventLogger eventLogger = new MintEventLogger(session, realm);
        return new MintResource(
                session,
                authenticator,
                eventLogger,
                schemaFetcher,
                validators,
                typeConfigLoader,
                tokenBuilder,
                maxPayloadBytes,
                maxPayloadDepth);
    }

    @Override
    public void close() {
        // session lifecycle managed by Keycloak
    }
}
