package com.example.keycloak.mint;

import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

public class MintEventLogger {

    private final KeycloakSession session;
    private final RealmModel realm;

    public MintEventLogger(KeycloakSession session, RealmModel realm) {
        this.session = session;
        this.realm = realm;
    }

    public void logSuccess(String clientId, String type, String audience, long ttlSeconds, String jti) {
        newEvent()
                .client(clientId)
                .detail("operation", "token-mint")
                .detail("mint_type", type)
                .detail("mint_audience", audience)
                .detail("mint_ttl_seconds", String.valueOf(ttlSeconds))
                .detail("mint_jti", jti)
                .success();
    }

    public void logFailure(String clientId, String type, String errorCode) {
        newEvent()
                .client(clientId)
                .detail("operation", "token-mint")
                .detail("mint_type", type)
                .error(errorCode);
    }

    private EventBuilder newEvent() {
        return new EventBuilder(realm, session, session.getContext().getConnection())
                .event(EventType.CLIENT_LOGIN);
    }
}
