package com.lendingplatform.quoteapp.vendor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How to reach the vendor (ARCHITECTURE §12). Validated at startup: without {@code VENDOR_API_KEY} the app refuses to
 * start, rather than failing on the first quote (ARCHITECTURE §9.1).
 *
 * @param apiKey sent as the {@code api-key} header; never logged, rendered or included in {@link #toString()}
 * @param maxRetries retries after the first attempt, for transient failures only; 0 disables retry
 * @param retryInitialBackoff delay before the first retry; doubles per retry, with jitter
 */
@Validated
@ConfigurationProperties(prefix = "vendor")
public record VendorClientProperties(
        @NotNull URI baseUrl,
        @NotBlank String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @PositiveOrZero int maxRetries,
        @NotNull Duration retryInitialBackoff) {

    public VendorClientProperties {
        requirePositive("vendor.connect-timeout", connectTimeout);
        requirePositive("vendor.read-timeout", readTimeout);
        requirePositive("vendor.retry-initial-backoff", retryInitialBackoff);
    }

    private static void requirePositive(String name, Duration value) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " must be a positive duration, but was " + value);
        }
    }

    @Override
    public String toString() {
        return "VendorClientProperties[baseUrl=" + baseUrl
                + ", apiKey=<redacted>"
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", maxRetries=" + maxRetries
                + ", retryInitialBackoff=" + retryInitialBackoff + "]";
    }
}
