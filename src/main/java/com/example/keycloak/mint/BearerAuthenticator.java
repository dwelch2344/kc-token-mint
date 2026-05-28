package com.example.keycloak.mint;

import org.keycloak.services.managers.AuthenticationManager;

@FunctionalInterface
public interface BearerAuthenticator {
    AuthenticationManager.AuthResult authenticate();
}
