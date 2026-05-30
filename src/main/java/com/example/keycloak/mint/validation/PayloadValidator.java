package com.example.keycloak.mint.validation;

import com.example.keycloak.mint.schema.FetchedSchema;
import com.fasterxml.jackson.databind.JsonNode;

public interface PayloadValidator {
    ValidationResult validate(JsonNode payload, FetchedSchema schema);
    boolean supports(String contentType);
}
