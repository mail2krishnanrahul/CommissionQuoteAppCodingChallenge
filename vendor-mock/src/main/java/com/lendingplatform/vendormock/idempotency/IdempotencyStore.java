package com.lendingplatform.vendormock.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Remembers the response for each Idempotency-Key so a retry, a double-click or "Try again" gets the original quote
 * instead of a new one.
 *
 * <p>In memory, with a TTL and a size bound. Entries are kept in insertion order, which is also age order, so when the
 * store is full the oldest entry is the one dropped.
 *
 * @param <T> the stored response type
 */
public class IdempotencyStore<T> {

    public record Lookup<T>(T response, boolean replayed) {
    }

    private record Entry<T>(String fingerprint, T response, Instant storedAt) {
    }

    private final Map<String, Entry<T>> entries;
    private final Clock clock;
    private final IdempotencyProperties properties;

    public IdempotencyStore(IdempotencyProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // synchronizedMap makes putIfAbsent atomic, which is what resolves concurrent duplicates to one response.
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry<T>> eldest) {
                return size() > properties.maxEntries();
            }
        });
    }

    /**
     * Returns the stored response for {@code key}, or creates, stores and returns a new one.
     *
     * @param fingerprint the normalised request, so the same key with a different body can be detected
     * @throws IdempotencyKeyReusedException if {@code key} was stored with a different fingerprint
     */
    public Lookup<T> findOrCreate(String key, String fingerprint, Supplier<T> create) {
        Entry<T> existing = liveEntry(key);
        if (existing != null) {
            return replay(existing, fingerprint);
        }

        // Create outside the lock: two concurrent duplicates may both compute, but only the first one is stored and
        // both callers return that one.
        Entry<T> candidate = new Entry<>(fingerprint, create.get(), clock.instant());
        Entry<T> winner = entries.putIfAbsent(key, candidate);
        return winner == null ? new Lookup<>(candidate.response(), false) : replay(winner, fingerprint);
    }

    private Entry<T> liveEntry(String key) {
        Entry<T> entry = entries.get(key);
        if (entry != null && isExpired(entry)) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    private boolean isExpired(Entry<T> entry) {
        return !clock.instant().isBefore(entry.storedAt().plus(properties.ttl()));
    }

    private static <T> Lookup<T> replay(Entry<T> entry, String fingerprint) {
        if (!entry.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        return new Lookup<>(entry.response(), true);
    }
}
