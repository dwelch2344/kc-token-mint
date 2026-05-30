package com.example.keycloak.mint.schema;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class SchemaCache {

    public static final class Entry {
        public final FetchedSchema schema;
        public final Instant fetchedAt;

        Entry(FetchedSchema schema, Instant fetchedAt) {
            this.schema = schema;
            this.fetchedAt = fetchedAt;
        }
    }

    private final Cache<String, Entry> cache;

    public SchemaCache(long ttlSeconds, long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }

    public Optional<Entry> get(String uri) {
        return Optional.ofNullable(cache.getIfPresent(uri));
    }

    public void put(String uri, FetchedSchema schema) {
        cache.put(uri, new Entry(schema, Instant.now()));
    }

    public void invalidate(String uri) {
        cache.invalidate(uri);
    }

    /** Force pending evictions — useful in tests with size-based bounds. */
    public void cleanUp() {
        cache.cleanUp();
    }
}
