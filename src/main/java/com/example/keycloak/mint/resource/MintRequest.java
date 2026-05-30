package com.example.keycloak.mint.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public class MintRequest {

    private String type;

    private JsonNode payload;

    @JsonProperty("ttl_seconds")
    private long ttlSeconds;

    private String audience;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }

    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
}
