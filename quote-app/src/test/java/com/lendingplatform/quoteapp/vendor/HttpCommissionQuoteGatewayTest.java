package com.lendingplatform.quoteapp.vendor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.lendingplatform.quoteapp.quote.CommissionQuote;
import com.lendingplatform.quoteapp.quote.LoanDetails;
import com.lendingplatform.quoteapp.quote.QuoteFailure;
import com.lendingplatform.quoteapp.quote.RiskBand;
import com.lendingplatform.quoteapp.web.CorrelationIdFilter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The vendor adapter against a real HTTP server, including real (short) timeouts. */
@ExtendWith(OutputCaptureExtension.class)
class HttpCommissionQuoteGatewayTest {

    private static final String API_KEY = "test-api-key-7f3a91";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(250);
    private static final int SLOWER_THAN_READ_TIMEOUT_MS = 1000;
    private static final LoanDetails LOAN = new LoanDetails(new BigDecimal("500000.00"), 360, RiskBand.MEDIUM);
    private static final String QUOTE_JSON = """
            {"quoteId":"3f6c2a9e-8d1b-4c7e-9a52-0b6f1d2e4a77","commissionRate":0.0060,"totalCommission":3000.00}""";

    @RegisterExtension
    static WireMockExtension vendor = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final String idempotencyKey = UUID.randomUUID().toString();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("sends the api-key, Idempotency-Key, correlation id, content type and the contract's body")
    void sendsEveryRequiredHeader() {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).willReturn(okJson(QUOTE_JSON)));
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");

        gateway(1).requestQuote(LOAN, idempotencyKey);

        vendor.verify(1, postRequestedFor(urlEqualTo("/v1/commission-quotes"))
                .withHeader("api-key", equalTo(API_KEY))
                .withHeader("Idempotency-Key", equalTo(idempotencyKey))
                .withHeader("X-Correlation-Id", equalTo("corr-123"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .withRequestBody(equalToJson("""
                        {"loanAmount":500000.00,"loanTermInMonths":360,"riskBand":"MEDIUM"}""")));
    }

    @Test
    @DisplayName("maps a success, ignoring fields the vendor might add later")
    void mapsSuccess() {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).willReturn(okJson("""
                {"quoteId":"q-1","commissionRate":0.0060,"totalCommission":3000.00,"validUntil":"2026-12-01"}""")));

        CommissionQuote quote = gateway(1).requestQuote(LOAN, idempotencyKey);

        assertThat(quote.quoteId()).isEqualTo("q-1");
        assertThat(quote.commissionRate()).isEqualByComparingTo("0.0060");
        assertThat(quote.totalCommission()).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("503 then 200 succeeds on the second attempt, with the same Idempotency-Key on both")
    void retriesTransientFailureWithSameIdempotencyKey() {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(QUOTE_JSON)));

        CommissionQuote quote = gateway(1).requestQuote(LOAN, idempotencyKey);

        assertThat(quote.quoteId()).isEqualTo("3f6c2a9e-8d1b-4c7e-9a52-0b6f1d2e4a77");
        vendor.verify(2, postRequestedFor(urlEqualTo("/v1/commission-quotes"))
                .withHeader("Idempotency-Key", equalTo(idempotencyKey)));
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("a transient 5xx on every attempt is VendorUnavailable after exactly max-retries + 1 attempts")
    void transientFailureExhaustsRetries(int status) {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> gateway(1).requestQuote(LOAN, idempotencyKey))
                .isInstanceOf(QuoteFailure.VendorUnavailable.class);
        vendor.verify(2, postRequestedFor(urlEqualTo("/v1/commission-quotes")));
    }

    @Test
    @DisplayName("a response slower than the read timeout is VendorTimeout")
    void slowResponseIsTimeout() {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH)
                .willReturn(okJson(QUOTE_JSON).withFixedDelay(SLOWER_THAN_READ_TIMEOUT_MS)));

        assertThatThrownBy(() -> gateway(1).requestQuote(LOAN, idempotencyKey))
                .isInstanceOf(QuoteFailure.VendorTimeout.class);
        vendor.verify(2, postRequestedFor(urlEqualTo("/v1/commission-quotes"))
                .withHeader("Idempotency-Key", equalTo(idempotencyKey)));
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {401, 403})
    @DisplayName("401/403 is VendorMisconfigured after exactly one request: retrying can't fix our key")
    void authFailureIsNotRetried(int status) {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> gateway(1).requestQuote(LOAN, idempotencyKey))
                .isInstanceOf(QuoteFailure.VendorMisconfigured.class);
        vendor.verify(1, postRequestedFor(urlEqualTo("/v1/commission-quotes")));
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {400, 404, 422})
    @DisplayName("any other 4xx is VendorRejected after exactly one request")
    void clientErrorIsNotRetried(int status) {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> gateway(1).requestQuote(LOAN, idempotencyKey))
                .isInstanceOf(QuoteFailure.VendorRejected.class);
        vendor.verify(1, postRequestedFor(urlEqualTo("/v1/commission-quotes")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"quoteId\":",
            "not json",
            "{\"commissionRate\":0.0060,\"totalCommission\":3000.00}",
            "{\"quoteId\":\"  \",\"commissionRate\":0.0060,\"totalCommission\":3000.00}",
            "{\"quoteId\":\"q-1\",\"totalCommission\":3000.00}",
            "{\"quoteId\":\"q-1\",\"commissionRate\":0.0060}",
            "{\"quoteId\":\"q-1\",\"commissionRate\":1.5,\"totalCommission\":3000.00}",
            "{\"quoteId\":\"q-1\",\"commissionRate\":-0.01,\"totalCommission\":3000.00}",
            "{\"quoteId\":\"q-1\",\"commissionRate\":0.0060,\"totalCommission\":-1}"
    })
    @DisplayName("a 200 we can't trust is VendorBadResponse, and isn't retried")
    void untrustworthySuccessIsBadResponse(String body) {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).willReturn(okJson(body)));

        assertThatThrownBy(() -> gateway(1).requestQuote(LOAN, idempotencyKey))
                .isInstanceOf(QuoteFailure.VendorBadResponse.class);
        vendor.verify(1, postRequestedFor(urlEqualTo("/v1/commission-quotes")));
    }

    @Test
    @DisplayName("connection refused is VendorUnavailable")
    void connectionRefusedIsUnavailable() throws IOException {
        URI nothingListening = URI.create("http://localhost:" + unusedPort());

        assertThatThrownBy(() -> gateway(nothingListening, 1).requestQuote(LOAN, idempotencyKey))
                .isInstanceOf(QuoteFailure.VendorUnavailable.class);
    }

    @Test
    @DisplayName("max-retries 0 makes exactly one attempt")
    void zeroRetriesMeansOneAttempt() {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> gateway(0).requestQuote(LOAN, idempotencyKey))
                .isInstanceOf(QuoteFailure.VendorUnavailable.class);
        vendor.verify(1, postRequestedFor(urlEqualTo("/v1/commission-quotes")));
    }

    @Test
    @DisplayName("logs every attempt and the outcome, but never the API key")
    void neverLogsTheApiKey(CapturedOutput output) {
        vendor.stubFor(post(HttpCommissionQuoteGateway.QUOTES_PATH).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> gateway(1).requestQuote(LOAN, idempotencyKey))
                .isInstanceOf(QuoteFailure.VendorUnavailable.class);

        assertThat(output).contains("vendor.attempt attempt=1", "retrying=true", "quote.outcome result=VendorUnavailable");
        assertThat(output).doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("the properties' toString never reveals the key")
    void propertiesRedactTheKey() {
        assertThat(properties(URI.create("http://localhost"), 1).toString())
                .doesNotContain(API_KEY)
                .contains("<redacted>");
    }

    private HttpCommissionQuoteGateway gateway(int maxRetries) {
        return gateway(URI.create(vendor.baseUrl()), maxRetries);
    }

    private static HttpCommissionQuoteGateway gateway(URI baseUrl, int maxRetries) {
        VendorClientProperties properties = properties(baseUrl, maxRetries);
        return new HttpCommissionQuoteGateway(new VendorClientConfig().vendorRestClient(properties), properties);
    }

    private static VendorClientProperties properties(URI baseUrl, int maxRetries) {
        return new VendorClientProperties(baseUrl, API_KEY, Duration.ofMillis(500), READ_TIMEOUT, maxRetries,
                Duration.ofMillis(10));
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
