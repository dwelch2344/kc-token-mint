package com.example.keycloak.mint;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MintResponse {

    private String token;

    @JsonProperty("expires_at")
    private long expiresAt;

    private String jti;

    public MintResponse(String token, long expiresAt, String jti) {
        this.token = token;
        this.expiresAt = expiresAt;
        this.jti = jti;
    }

    public String getToken() { return token; }
    public long getExpiresAt() { return expiresAt; }
    public String getJti() { return jti; }
}
