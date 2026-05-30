package com.example.keycloak.mint.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MintErrorResponse {

    private final String error;

    @JsonProperty("error_description")
    private final String errorDescription;

    public MintErrorResponse(String error, String errorDescription) {
        this.error = error;
        this.errorDescription = errorDescription;
    }

    public String getError() { return error; }
    public String getErrorDescription() { return errorDescription; }

    public static MintErrorResponse unauthorized() {
        return new MintErrorResponse("unauthorized", "Bearer token missing or invalid");
    }

    public static MintErrorResponse forbidden(String description) {
        return new MintErrorResponse("access_denied", description);
    }

    public static MintErrorResponse badRequest(String description) {
        return new MintErrorResponse("invalid_request", description);
    }

    public static MintErrorResponse schemaFetchFailed() {
        return new MintErrorResponse("schema_unavailable", "Unable to fetch validation schema");
    }

    public static MintErrorResponse payloadInvalid(String description) {
        return new MintErrorResponse("payload_invalid", description);
    }

    public static MintErrorResponse internalError(String description) {
        return new MintErrorResponse("server_error", description);
    }
}
