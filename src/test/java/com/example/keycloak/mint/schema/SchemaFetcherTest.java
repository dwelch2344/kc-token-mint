package com.example.keycloak.mint.schema;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaFetcherTest {

    private static final String SCHEMA_JSON =
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}";

    private WireMockServer server;
    private SchemaCache cache;
    private SchemaFetcher fetcher;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        cache = new SchemaCache(60, 100);
        // Allow HTTP and private IPs since WireMock runs on localhost
        fetcher = new SchemaFetcher(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                cache,
                true,   // allowHttpSchemas
                true,   // allowPrivateIpSchemas
                3600,   // stalenessWindowSeconds
                5000);  // readTimeoutMs
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void fetchesSchemaSuccessfully() {
        server.stubFor(get(urlEqualTo("/schema.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SCHEMA_JSON)));

        URI uri = URI.create("http://localhost:" + server.port() + "/schema.json");
        FetchedSchema schema = fetcher.fetch(uri, Optional.empty());

        assertNotNull(schema);
        assertEquals("application/json", schema.contentType());
        assertTrue(schema.schemaNode().has("type"));
    }

    @Test
    void contentTypeOverrideIsRespected() {
        server.stubFor(get(urlEqualTo("/schema.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(SCHEMA_JSON)));

        URI uri = URI.create("http://localhost:" + server.port() + "/schema.json");
        FetchedSchema schema = fetcher.fetch(uri, Optional.of("application/schema+json"));

        assertEquals("application/schema+json", schema.contentType());
    }

    @Test
    void conditionalGetReturns304() {
        server.stubFor(get(urlEqualTo("/schema.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"v1\"")
                        .withBody(SCHEMA_JSON)));

        URI uri = URI.create("http://localhost:" + server.port() + "/schema.json");
        fetcher.fetch(uri, Optional.empty());

        server.resetMappings();
        server.stubFor(get(urlEqualTo("/schema.json"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));

        FetchedSchema second = fetcher.fetch(uri, Optional.empty());
        assertNotNull(second);
        assertEquals("application/json", second.contentType());
        server.verify(1, getRequestedFor(urlEqualTo("/schema.json"))
                .withHeader("If-None-Match", equalTo("\"v1\"")));
    }

    @Test
    void fallsBackToStaleCacheOnFetchFailure() {
        server.stubFor(get(urlEqualTo("/schema.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SCHEMA_JSON)));

        URI uri = URI.create("http://localhost:" + server.port() + "/schema.json");
        fetcher.fetch(uri, Optional.empty());

        server.resetMappings();
        server.stubFor(get(urlEqualTo("/schema.json"))
                .willReturn(aResponse().withStatus(503)));

        // Should fall back to stale cached schema within the staleness window
        FetchedSchema fallback = fetcher.fetch(uri, Optional.empty());
        assertNotNull(fallback);
        assertEquals("application/json", fallback.contentType());
    }

    @Test
    void httpsSchemeRejectedByDefault() {
        SchemaFetcher strictFetcher = new SchemaFetcher(
                HttpClient.newBuilder().build(),
                new SchemaCache(60, 10),
                false,  // no HTTP
                true,
                3600,
                5000);

        assertThrows(SchemaFetchException.class, () ->
                strictFetcher.fetch(URI.create("http://example.com/s.json"), Optional.empty()));
    }

    @Test
    void privateIpRejectedByDefault() {
        SchemaFetcher strictFetcher = new SchemaFetcher(
                HttpClient.newBuilder().build(),
                new SchemaCache(60, 10),
                true,
                false,  // no private IPs
                3600,
                5000);

        assertThrows(SchemaFetchException.class, () ->
                strictFetcher.fetch(URI.create("http://127.0.0.1/s.json"), Optional.empty()));
    }

    @Test
    void schemaIsCachedAfterFirstFetch() {
        server.stubFor(get(urlEqualTo("/schema.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(SCHEMA_JSON)));

        URI uri = URI.create("http://localhost:" + server.port() + "/schema.json");
        fetcher.fetch(uri, Optional.empty());

        assertTrue(cache.get(uri.toString()).isPresent());
    }
}
