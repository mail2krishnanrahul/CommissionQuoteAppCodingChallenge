package com.lendingplatform.quoteapp.vendor;

import com.lendingplatform.quoteapp.quote.CommissionQuote;
import com.lendingplatform.quoteapp.quote.CommissionQuoteGateway;
import com.lendingplatform.quoteapp.quote.LoanDetails;
import com.lendingplatform.quoteapp.quote.QuoteFailure;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorBadResponse;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorMisconfigured;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorRejected;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorTimeout;
import com.lendingplatform.quoteapp.quote.QuoteFailure.VendorUnavailable;
import com.lendingplatform.quoteapp.web.CorrelationIdFilter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

/**
 * Calls the vendor's Commission Quote API and turns every outcome into a {@link CommissionQuote} or a
 * {@link QuoteFailure}.
 *
 * <p>Retry policy (ARCHITECTURE §8, ADR-005): bounded, with exponential backoff and jitter, only for transient failures
 * (500/502/503/504, connection failures, read timeouts), never for a 4xx. Every attempt carries the <em>same</em>
 * Idempotency-Key, so if an attempt timed out after the vendor had already created a quote, the retry gets that quote
 * back instead of creating a second one.
 *
 * <p>The API key is added to outbound requests here and nowhere else. It is never logged.
 */
@Component
public class HttpCommissionQuoteGateway implements CommissionQuoteGateway {

    static final String QUOTES_PATH = "/v1/commission-quotes";
    static final String API_KEY_HEADER = "api-key";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(HttpCommissionQuoteGateway.class);
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final RestClient restClient;
    private final VendorClientProperties properties;
    private final RetryTemplate retryTemplate;

    public HttpCommissionQuoteGateway(RestClient vendorRestClient, VendorClientProperties properties) {
        this.restClient = vendorRestClient;
        this.properties = properties;
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.maxRetries())
                .delay(properties.retryInitialBackoff())
                .multiplier(BACKOFF_MULTIPLIER)
                // Jitter spreads retries out so many clients failing at once don't all retry at the same instant.
                .jitter(properties.retryInitialBackoff().dividedBy(2))
                .predicate(HttpCommissionQuoteGateway::isTransient)
                .build());
    }

    @Override
    public CommissionQuote requestQuote(LoanDetails loanDetails, String idempotencyKey) {
        VendorQuoteRequest body = VendorQuoteRequest.from(loanDetails);
        AtomicInteger attempts = new AtomicInteger();
        long startedAt = System.nanoTime();
        try {
            CommissionQuote quote = retryTemplate.execute(() -> attempt(body, idempotencyKey, attempts.incrementAndGet()));
            log.info("quote.outcome result=SUCCESS band={} termMonths={} attempts={} durationMs={} quoteId={}",
                    loanDetails.riskBand(), loanDetails.loanTermInMonths(), attempts.get(), millisSince(startedAt),
                    quote.quoteId());
            return quote;
        } catch (RetryException e) {
            QuoteFailure failure = asQuoteFailure(e.getCause());
            log.info("quote.outcome result={} band={} termMonths={} attempts={} durationMs={}",
                    failure.getClass().getSimpleName(), loanDetails.riskBand(), loanDetails.loanTermInMonths(),
                    attempts.get(), millisSince(startedAt));
            throw failure;
        }
    }

    private CommissionQuote attempt(VendorQuoteRequest body, String idempotencyKey, int attempt) {
        long startedAt = System.nanoTime();
        try {
            CommissionQuote quote = toQuote(post(body, idempotencyKey));
            log.debug("vendor.attempt attempt={} outcome=OK durationMs={}", attempt, millisSince(startedAt));
            return quote;
        } catch (QuoteFailure failure) {
            boolean retrying = isTransient(failure) && attempt <= properties.maxRetries();
            if (retrying) {
                log.warn("vendor.attempt attempt={} outcome={} detail=\"{}\" durationMs={} retrying=true",
                        attempt, failure.getClass().getSimpleName(), failure.getMessage(), millisSince(startedAt));
            } else {
                log.debug("vendor.attempt attempt={} outcome={} detail=\"{}\" durationMs={} retrying=false",
                        attempt, failure.getClass().getSimpleName(), failure.getMessage(), millisSince(startedAt));
            }
            throw failure;
        }
    }

    private VendorQuoteResponse post(VendorQuoteRequest body, String idempotencyKey) {
        String correlationId = CorrelationIdFilter.current();
        try {
            return restClient.post()
                    .uri(QUOTES_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set(API_KEY_HEADER, properties.apiKey());
                        headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
                        if (correlationId != null) {
                            headers.set(CorrelationIdFilter.HEADER, correlationId);
                        }
                    })
                    .body(body)
                    .exchange((request, response) -> readBody(response));
        } catch (ResourceAccessException e) {
            throw isReadTimeout(e)
                    ? new VendorTimeout("no response within " + properties.readTimeout(), e)
                    : new VendorUnavailable("could not reach vendor: " + rootCauseName(e), e);
        }
    }

    private static VendorQuoteResponse readBody(ConvertibleClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            throw failureFor(status);
        }
        try {
            return response.bodyTo(VendorQuoteResponse.class);
        } catch (HttpMessageConversionException | RestClientException e) {
            throw new VendorBadResponse("unreadable response body", e);
        }
    }

    /** Classifies a non-2xx status. Only 500/502/503/504 are worth retrying. */
    private static QuoteFailure failureFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 401, 403 -> new VendorMisconfigured("vendor rejected our credentials: status=" + status.value());
            case 500, 502, 503, 504 -> new VendorUnavailable("vendor error: status=" + status.value(), null);
            default -> status.is4xxClientError()
                    ? new VendorRejected("vendor rejected the request: status=" + status.value())
                    : new VendorBadResponse("unexpected status=" + status.value(), null);
        };
    }

    /** Tolerant of extra fields, strict about the ones we need (ADR-011). */
    private static CommissionQuote toQuote(VendorQuoteResponse response) {
        if (response == null) {
            throw new VendorBadResponse("empty response body", null);
        }
        if (response.quoteId() == null || response.quoteId().isBlank()) {
            throw new VendorBadResponse("missing quoteId", null);
        }
        BigDecimal rate = response.commissionRate();
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new VendorBadResponse("commissionRate missing or outside [0, 1]: " + rate, null);
        }
        BigDecimal total = response.totalCommission();
        if (total == null || total.signum() < 0) {
            throw new VendorBadResponse("totalCommission missing or negative: " + total, null);
        }
        return new CommissionQuote(response.quoteId(), rate, total);
    }

    private static boolean isTransient(Throwable failure) {
        return failure instanceof VendorUnavailable || failure instanceof VendorTimeout;
    }

    /** A connect timeout means the vendor is unreachable (unavailable); only a slow response is a timeout. */
    private static boolean isReadTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static QuoteFailure asQuoteFailure(Throwable cause) {
        if (cause instanceof QuoteFailure failure) {
            return failure;
        }
        if (cause instanceof RuntimeException unexpected) {
            throw unexpected;
        }
        throw new IllegalStateException("Unexpected failure calling the vendor", cause);
    }

    private static String rootCauseName(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static long millisSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
