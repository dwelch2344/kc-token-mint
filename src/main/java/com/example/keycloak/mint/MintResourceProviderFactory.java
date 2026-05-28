package com.example.keycloak.mint;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

public class MintResourceProviderFactory implements RealmResourceProviderFactory {

    private static final String PROVIDER_ID = "mint";

    // Shared, long-lived resources
    private HttpClient httpClient;
    private SchemaCache schemaCache;
    private SchemaFetcher schemaFetcher;
    private List<PayloadValidator> validators;
    private TypeConfigLoader typeConfigLoader;
    private TokenBuilder tokenBuilder;

    // Config-driven tuning knobs
    private int maxPayloadBytes;
    private int maxPayloadDepth;

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(Config.Scope config) {
        long schemaCacheTtlSeconds = config.getLong("schema-cache-ttl-seconds", 300L);
        long schemaCacheMaxSize = config.getLong("schema-cache-max-size", 1000L);
        long connectTimeoutMs = config.getLong("schema-fetch-connect-timeout-ms", 2000L);
        long readTimeoutMs = config.getLong("schema-fetch-read-timeout-ms", 5000L);
        long stalenessWindowSeconds = config.getLong("schema-staleness-window-seconds", 3600L);
        boolean allowHttpSchemas = config.getBoolean("allow-http-schemas", false);
        boolean allowPrivateIpSchemas = config.getBoolean("allow-private-ip-schemas", false);
        maxPayloadBytes = config.getInt("max-payload-bytes", 4096);
        maxPayloadDepth = config.getInt("max-payload-depth", 8);

        schemaCache = new SchemaCache(schemaCacheTtlSeconds, schemaCacheMaxSize);

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        schemaFetcher = new SchemaFetcher(
                httpClient,
                schemaCache,
                allowHttpSchemas,
                allowPrivateIpSchemas,
                stalenessWindowSeconds,
                readTimeoutMs);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        validators = List.of(new JsonSchemaPayloadValidator());
        typeConfigLoader = new TypeConfigLoader();
        tokenBuilder = new TokenBuilder();
    }

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new MintResourceProvider(
                session,
                schemaFetcher,
                validators,
                typeConfigLoader,
                tokenBuilder,
                maxPayloadBytes,
                maxPayloadDepth);
    }

    @Override
    public void close() {
        // HttpClient and Caffeine cache are GC-collected; nothing explicit to close
    }
}
