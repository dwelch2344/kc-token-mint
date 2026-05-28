package com.example.keycloak.mint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaPayloadValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSchemaPayloadValidator validator;

    private static final String CAPABILITY_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["resource", "operations"],
              "properties": {
                "resource": { "type": "string" },
                "operations": { "type": "array", "items": { "type": "string" } }
              },
              "additionalProperties": false
            }
            """;

    @BeforeEach
    void setUp() {
        validator = new JsonSchemaPayloadValidator();
    }

    @Test
    void supportsApplicationJson() {
        assertTrue(validator.supports("application/json"));
    }

    @Test
    void supportsSchemaJson() {
        assertTrue(validator.supports("application/schema+json"));
    }

    @Test
    void supportsWithCharset() {
        assertTrue(validator.supports("application/json; charset=utf-8"));
    }

    @Test
    void doesNotSupportXml() {
        assertFalse(validator.supports("application/xml"));
    }

    @Test
    void doesNotSupportNull() {
        assertFalse(validator.supports(null));
    }

    @Test
    void validPayloadPassesValidation() throws Exception {
        FetchedSchema schema = schemaFor(CAPABILITY_SCHEMA);
        JsonNode payload = MAPPER.readTree("""
                {"resource": "orders", "operations": ["read", "write"]}
                """);

        ValidationResult result = validator.validate(payload, schema);
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void missingRequiredFieldFails() throws Exception {
        FetchedSchema schema = schemaFor(CAPABILITY_SCHEMA);
        JsonNode payload = MAPPER.readTree("""
                {"resource": "orders"}
                """);

        ValidationResult result = validator.validate(payload, schema);
        assertFalse(result.valid());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void wrongTypeFails() throws Exception {
        FetchedSchema schema = schemaFor(CAPABILITY_SCHEMA);
        JsonNode payload = MAPPER.readTree("""
                {"resource": 42, "operations": ["read"]}
                """);

        ValidationResult result = validator.validate(payload, schema);
        assertFalse(result.valid());
    }

    @Test
    void additionalPropertyFails() throws Exception {
        FetchedSchema schema = schemaFor(CAPABILITY_SCHEMA);
        JsonNode payload = MAPPER.readTree("""
                {"resource": "orders", "operations": ["read"], "extra": "field"}
                """);

        ValidationResult result = validator.validate(payload, schema);
        assertFalse(result.valid());
    }

    @Test
    void emptyObjectAgainstPermissiveSchema() throws Exception {
        FetchedSchema schema = schemaFor("{\"type\": \"object\"}");
        JsonNode payload = MAPPER.readTree("{}");
        assertTrue(validator.validate(payload, schema).valid());
    }

    private FetchedSchema schemaFor(String json) throws Exception {
        return new FetchedSchema(MAPPER.readTree(json), "application/json",
                Optional.empty(), Optional.empty());
    }
}
