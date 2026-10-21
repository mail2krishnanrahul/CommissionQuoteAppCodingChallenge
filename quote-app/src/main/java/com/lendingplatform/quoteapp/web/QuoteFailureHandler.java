package com.lendingplatform.quoteapp.web;

import com.lendingplatform.quoteapp.quote.QuoteFailure;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorBadResponse;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorMisconfigured;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorRejected;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorTimeout;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorUnavailable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The one place that decides what each failure means for the user: HTTP status, message, and whether "Try again" is
 * offered (ARCHITECTURE §7.2). The {@code switch} is exhaustive over the sealed {@link QuoteFailure}, so a new failure
 * type doesn't compile until it is handled here (ADR-006).
 *
 * <p>Upstream failures return 502/503/504 rather than 200 so access logs and monitoring see real error rates
 * (ADR-012). A vendor 401 becomes 502: the user isn't unauthenticated, our integration is misconfigured. Messages are
 * fixed text, never the exception's message, so nothing internal can leak to the page.
 */
@Component
public class QuoteFailureHandler {

    public record FailureView(HttpStatus status, String message, boolean retryable) {
    }

    public FailureView handle(QuoteFailure failure) {
        return switch (failure) {
            case VendorUnavailable unavailable -> new FailureView(HttpStatus.SERVICE_UNAVAILABLE,
                    "The quote service is temporarily unavailable. Your details are still here — please try again.",
                    true);
            case VendorTimeout timeout -> new FailureView(HttpStatus.GATEWAY_TIMEOUT,
                    "The quote service took too long to respond. Please try again.",
                    true);
            case VendorMisconfigured misconfigured -> new FailureView(HttpStatus.BAD_GATEWAY,
                    "We can't get a quote right now because of a configuration problem on our side. "
                            + "Please contact support with the reference below.",
                    false);
            case VendorRejected rejected -> new FailureView(HttpStatus.BAD_GATEWAY,
                    "The quote service couldn't process these loan details. "
                            + "Please check them, or contact support with the reference below.",
                    false);
            case VendorBadResponse badResponse -> new FailureView(HttpStatus.BAD_GATEWAY,
                    "We received an unexpected response from the quote service. Please try again later.",
                    true);
        };
    }

    /** For a bug: anything that isn't a {@link QuoteFailure}. */
    public FailureView unexpected() {
        return new FailureView(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Please try again.",
                true);
    }
}
