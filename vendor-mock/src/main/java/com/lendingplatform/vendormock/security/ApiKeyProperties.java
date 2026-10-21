package com.lendingplatform.vendormock.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The api-key values the mock accepts. A set rather than a single value so a key can be rotated with an overlap
 * window. There is no default outside the {@code local} profile: the mock refuses to start without a key.
 */
@ConfigurationProperties(prefix = "mock")
public record ApiKeyProperties(Set<String> apiKeys) {

    public ApiKeyProperties {
        apiKeys = apiKeys == null ? Set.of() : apiKeys.stream()
                .map(String::strip)
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (apiKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "mock.api-keys must contain at least one key: set MOCK_API_KEYS, or run with the 'local' profile");
        }
    }

    @Override
    public String toString() {
        return "ApiKeyProperties[apiKeys=<" + apiKeys.size() + " redacted>]";
    }
}
