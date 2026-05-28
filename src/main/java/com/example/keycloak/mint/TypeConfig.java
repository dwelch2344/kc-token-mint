package com.example.keycloak.mint;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public record TypeConfig(
        String type,
        URI schemaUri,
        Optional<String> schemaContentTypeOverride,
        Duration maxTtl,
        Set<String> allowedAudiences
) {}
