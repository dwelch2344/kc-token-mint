package com.example.keycloak.mint;

import com.fasterxml.jackson.databind.JsonNode;

public interface PayloadValidator {
    ValidationResult validate(JsonNode payload, FetchedSchema schema);
    boolean supports(String contentType);
}
