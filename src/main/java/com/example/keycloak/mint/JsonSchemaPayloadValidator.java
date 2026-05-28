package com.example.keycloak.mint;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Set;

public class JsonSchemaPayloadValidator implements PayloadValidator {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "application/schema+json",
            "application/json"
    );

    private final JsonSchemaFactory factory;

    public JsonSchemaPayloadValidator() {
        this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }

    @Override
    public boolean supports(String contentType) {
        if (contentType == null) return false;
        String normalized = contentType.split(";")[0].trim().toLowerCase();
        return SUPPORTED_TYPES.contains(normalized);
    }

    @Override
    public ValidationResult validate(JsonNode payload, FetchedSchema schema) {
        try {
            JsonSchema jsonSchema = factory.getSchema(schema.schemaNode());
            Set<ValidationMessage> errors = jsonSchema.validate(payload);
            if (errors.isEmpty()) {
                return ValidationResult.ok();
            }
            List<String> messages = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .toList();
            return ValidationResult.fail(messages);
        } catch (Exception e) {
            return ValidationResult.fail("Schema validation error: " + e.getMessage());
        }
    }
}
