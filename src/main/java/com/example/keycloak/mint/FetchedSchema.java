package com.example.keycloak.mint;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

public record FetchedSchema(
        JsonNode schemaNode,
        String contentType,
        Optional<String> etag,
        Optional<String> lastModified
) {}
