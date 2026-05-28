package com.example.keycloak.mint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SchemaCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FetchedSchema schema(String json) throws Exception {
        return new FetchedSchema(MAPPER.readTree(json), "application/json",
                Optional.of("\"abc\""), Optional.empty());
    }

    @Test
    void putAndGetReturnsEntry() throws Exception {
        SchemaCache cache = new SchemaCache(60, 100);
        FetchedSchema s = schema("{\"type\":\"object\"}");
        cache.put("http://example.com/s.json", s);

        Optional<SchemaCache.Entry> result = cache.get("http://example.com/s.json");
        assertTrue(result.isPresent());
        assertEquals("application/json", result.get().schema.contentType());
    }

    @Test
    void missOnUnknownKey() {
        SchemaCache cache = new SchemaCache(60, 100);
        assertTrue(cache.get("http://unknown.example.com/s.json").isEmpty());
    }

    @Test
    void invalidateRemovesEntry() throws Exception {
        SchemaCache cache = new SchemaCache(60, 100);
        cache.put("http://example.com/s.json", schema("{}"));
        cache.invalidate("http://example.com/s.json");
        assertTrue(cache.get("http://example.com/s.json").isEmpty());
    }

    @Test
    void etagIsPreserved() throws Exception {
        SchemaCache cache = new SchemaCache(60, 100);
        FetchedSchema s = new FetchedSchema(MAPPER.readTree("{}"), "application/json",
                Optional.of("\"etag-value\""), Optional.of("Mon, 01 Jan 2024 00:00:00 GMT"));
        cache.put("http://example.com/s.json", s);

        SchemaCache.Entry entry = cache.get("http://example.com/s.json").orElseThrow();
        assertEquals(Optional.of("\"etag-value\""), entry.schema.etag());
        assertEquals(Optional.of("Mon, 01 Jan 2024 00:00:00 GMT"), entry.schema.lastModified());
    }

    @Test
    void maxSizeEvicts() throws Exception {
        SchemaCache cache = new SchemaCache(60, 3);
        for (int i = 0; i < 10; i++) {
            cache.put("http://example.com/s" + i + ".json", schema("{}"));
        }
        // Caffeine schedules eviction asynchronously; cleanUp() forces it synchronously
        cache.cleanUp();
        long present = 0;
        for (int i = 0; i < 10; i++) {
            if (cache.get("http://example.com/s" + i + ".json").isPresent()) present++;
        }
        assertTrue(present <= 3, "Cache should not hold more than maxSize entries");
    }

    @Test
    void fetchedAtIsSetOnPut() throws Exception {
        SchemaCache cache = new SchemaCache(60, 100);
        long before = System.currentTimeMillis();
        cache.put("http://example.com/s.json", schema("{}"));
        long after = System.currentTimeMillis();

        SchemaCache.Entry entry = cache.get("http://example.com/s.json").orElseThrow();
        long fetchedMs = entry.fetchedAt.toEpochMilli();
        assertTrue(fetchedMs >= before && fetchedMs <= after);
    }
}
