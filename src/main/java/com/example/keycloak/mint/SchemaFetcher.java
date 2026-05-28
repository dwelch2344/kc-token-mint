package com.example.keycloak.mint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class SchemaFetcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final SchemaCache cache;
    private final boolean allowHttpSchemas;
    private final boolean allowPrivateIpSchemas;
    private final long stalenessWindowSeconds;
    private final long readTimeoutMs;

    public SchemaFetcher(
            HttpClient httpClient,
            SchemaCache cache,
            boolean allowHttpSchemas,
            boolean allowPrivateIpSchemas,
            long stalenessWindowSeconds,
            long readTimeoutMs) {
        this.httpClient = httpClient;
        this.cache = cache;
        this.allowHttpSchemas = allowHttpSchemas;
        this.allowPrivateIpSchemas = allowPrivateIpSchemas;
        this.stalenessWindowSeconds = stalenessWindowSeconds;
        this.readTimeoutMs = readTimeoutMs;
    }

    public FetchedSchema fetch(URI schemaUri, Optional<String> contentTypeOverride) {
        validateScheme(schemaUri);
        validateNotPrivateIp(schemaUri);

        Optional<SchemaCache.Entry> cached = cache.get(schemaUri.toString());

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(schemaUri)
                .GET()
                .timeout(Duration.ofMillis(readTimeoutMs));
        cached.ifPresent(entry -> {
            entry.schema.etag().ifPresent(etag ->
                    reqBuilder.header("If-None-Match", etag));
            entry.schema.lastModified().ifPresent(lm ->
                    reqBuilder.header("If-Modified-Since", lm));
        });

        try {
            HttpResponse<String> response = httpClient.send(
                    reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 304 && cached.isPresent()) {
                FetchedSchema refreshed = cached.get().schema;
                cache.put(schemaUri.toString(), refreshed);
                return refreshed;
            }

            if (response.statusCode() == 200) {
                String rawContentType = contentTypeOverride.orElseGet(
                        () -> response.headers().firstValue("Content-Type").orElse(null));
                if (rawContentType == null) {
                    throw new SchemaFetchException("No Content-Type on schema response and no override configured", false);
                }
                String contentType = rawContentType.split(";")[0].trim().toLowerCase();

                JsonNode schemaNode = MAPPER.readTree(response.body());
                Optional<String> etag = response.headers().firstValue("ETag");
                Optional<String> lastModified = response.headers().firstValue("Last-Modified");
                FetchedSchema fetched = new FetchedSchema(schemaNode, contentType, etag, lastModified);
                cache.put(schemaUri.toString(), fetched);
                return fetched;
            }

            throw new SchemaFetchException(
                    "Schema fetch returned status " + response.statusCode(), true);

        } catch (SchemaFetchException e) {
            // Non-transient errors (scheme violation, SSRF) fail immediately; transient ones fall back
            if (!e.isTransient()) throw e;
            return fallbackOrThrow(schemaUri, e);
        } catch (Exception e) {
            return fallbackOrThrow(schemaUri, e);
        }
    }

    private FetchedSchema fallbackOrThrow(URI schemaUri, Exception cause) {
        Optional<SchemaCache.Entry> cached = cache.get(schemaUri.toString());
        if (cached.isPresent()) {
            Duration staleness = Duration.between(cached.get().fetchedAt, Instant.now());
            if (staleness.getSeconds() <= stalenessWindowSeconds) {
                return cached.get().schema;
            }
        }
        throw new SchemaFetchException("Schema fetch failed and no usable cached entry", cause, true);
    }

    private void validateScheme(URI uri) {
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) return;
        if ("http".equalsIgnoreCase(scheme) && allowHttpSchemas) return;
        throw new SchemaFetchException(
                "Schema URI scheme '" + scheme + "' is not allowed", false);
    }

    private void validateNotPrivateIp(URI uri) {
        if (allowPrivateIpSchemas) return;
        try {
            InetAddress addr = InetAddress.getByName(uri.getHost());
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                throw new SchemaFetchException(
                        "Schema URI resolves to a private/loopback address: " + uri.getHost(), false);
            }
        } catch (SchemaFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaFetchException("Could not resolve schema host: " + uri.getHost(), e, false);
        }
    }
}
