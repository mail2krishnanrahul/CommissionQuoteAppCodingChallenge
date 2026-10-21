package com.lendingplatform.vendormock.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ttl how long a stored response can be replayed
 * @param maxEntries upper bound on stored responses, so memory stays bounded however many keys arrive
 */
@ConfigurationProperties(prefix = "mock.idempotency")
public record IdempotencyProperties(Duration ttl, int maxEntries) {

    public IdempotencyProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("mock.idempotency.ttl must be a positive duration, but was " + ttl);
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("mock.idempotency.max-entries must be at least 1, but was " + maxEntries);
        }
    }
}
