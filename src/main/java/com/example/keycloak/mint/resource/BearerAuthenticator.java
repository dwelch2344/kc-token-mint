package com.example.keycloak.mint.resource;

import org.keycloak.services.managers.AuthenticationManager;

@FunctionalInterface
public interface BearerAuthenticator {
    AuthenticationManager.AuthResult authenticate();
}
