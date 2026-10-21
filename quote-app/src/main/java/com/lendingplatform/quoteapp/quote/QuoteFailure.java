package com.lendingplatform.quoteapp.quote;

/**
 * Every way getting a quote from the vendor can fail (ARCHITECTURE §7.2).
 *
 * <p>Sealed, so {@code QuoteFailureHandler}'s pattern-matching {@code switch} is checked by the compiler: adding a
 * subtype here is a compile error until it is handled (ADR-006). Messages are for logs only, never for users, and
 * never contain the API key.
 */
public abstract sealed class QuoteFailure extends RuntimeException {

    private QuoteFailure(String message, Throwable cause) {
        super(message, cause);
    }

    /** Vendor 500/502/503/504, or it couldn't be reached at all. Transient: retried, then "Try again". */
    public static final class VendorUnavailable extends QuoteFailure {
        public VendorUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** No response within the read timeout. Transient: retried, then "Try again". */
    public static final class VendorTimeout extends QuoteFailure {
        public VendorTimeout(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Vendor 401/403: our key or configuration is wrong. Retrying can't help. */
    public static final class VendorMisconfigured extends QuoteFailure {
        public VendorMisconfigured(String message) {
            super(message, null);
        }
    }

    /** Any other vendor 4xx: it refused this request. Retrying the same request can't help. */
    public static final class VendorRejected extends QuoteFailure {
        public VendorRejected(String message) {
            super(message, null);
        }
    }

    /** A response we can't trust: unparseable, missing a field, or with impossible values. */
    public static final class VendorBadResponse extends QuoteFailure {
        public VendorBadResponse(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
