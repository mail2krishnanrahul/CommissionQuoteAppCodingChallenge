package com.lendingplatform.quoteapp.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lendingplatform.quoteapp.quote.CommissionQuote;
import com.lendingplatform.quoteapp.quote.LoanDetails;
import com.lendingplatform.quoteapp.quote.QuoteFailure;
import com.lendingplatform.quoteapp.quote.QuoteService;
import com.lendingplatform.quoteapp.quote.RiskBand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Every UI state in ARCHITECTURE §7.3 that the server renders, through the real template. */
@WebMvcTest(QuoteController.class)
@Import({LoanDetailsParser.class, QuoteFailureHandler.class, QuoteControllerTest.FixedClock.class})
class QuoteControllerTest {

    private static final String KEY = "6f1c3c4e-1111-4a2b-9c3d-123456789abc";
    private static final Pattern HIDDEN_KEY = Pattern.compile("name=\"idempotencyKey\" value=\"([^\"]+)\"");
    /** Thymeleaf writes {@code autofocus="autofocus"}; static markup writes a bare {@code autofocus}. */
    private static final Pattern AUTOFOCUS_ATTRIBUTE = Pattern.compile("\\sautofocus(?:=\"autofocus\")?[\\s>]");
    private static final LoanDetails LOAN = new LoanDetails(new BigDecimal("500000.00"), 360, RiskBand.MEDIUM);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            // 2026-10-21 09:30 in Sydney (AEDT, UTC+11).
            return Clock.fixed(Instant.parse("2026-10-20T22:30:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QuoteService quoteService;

    @Test
    @DisplayName("GET / renders the form, with a fresh idempotency key and the security headers")
    void rendersForm() throws Exception {
        MvcResult result = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().string(containsString("<!DOCTYPE html>")))
                .andExpect(content().string(containsString("Generate Quote")))
                .andExpect(content().string(containsString("name=\"htmx-config\"")))
                .andExpect(content().string(containsString("inputmode=\"decimal\"")))
                .andExpect(content().string(containsString("<legend>Risk band</legend>")))
                .andReturn();

        assertThat(UUID.fromString(idempotencyKeyIn(result))).isNotNull();
    }

    @Test
    @DisplayName("a valid htmx POST returns the panel with the formatted quote and a new idempotency key")
    void successRendersQuote() throws Exception {
        given(quoteService.requestQuote(LOAN, KEY))
                .willReturn(new CommissionQuote("3f6c2a9e-quote", new BigDecimal("0.0060"), new BigDecimal("3000.00")));

        MvcResult result = mvc.perform(htmxPost("500,000", "360", "MEDIUM", KEY))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<!DOCTYPE html>"))))
                .andExpect(content().string(containsString("id=\"quote-panel\"")))
                .andExpect(content().string(containsString("$3,000.00")))
                .andExpect(content().string(containsString("0.60%")))
                .andExpect(content().string(containsString("3f6c2a9e-quote")))
                .andExpect(content().string(containsString("$500,000.00")))
                .andExpect(content().string(containsString("360 months")))
                .andExpect(content().string(containsString("Medium")))
                .andExpect(content().string(containsString("21 Oct 2026, 9:30:00 am AEDT")))
                .andExpect(content().string(containsString("id=\"result-heading\" tabindex=\"-1\" autofocus")))
                .andReturn();

        assertThat(idempotencyKeyIn(result)).as("next submission is a new quote").isNotEqualTo(KEY);
        assertThat(result.getResponse().getContentAsString()).as("values kept after success").contains("value=\"500,000\"");
    }

    @Test
    @DisplayName("invalid input returns 422 with inline errors, the user's values, the same key, and focus on the first bad field")
    void invalidInputRendersFieldErrors() throws Exception {
        MvcResult result = mvc.perform(htmxPost("12abc", "12.5", null, KEY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString(
                        "Enter the loan amount as a number, for example 250000 or 250,000.")))
                .andExpect(content().string(containsString("Enter the loan term as a whole number of months.")))
                .andExpect(content().string(containsString("Select a risk band.")))
                .andExpect(content().string(containsString("value=\"12abc\"")))
                .andExpect(content().string(containsString("value=\"12.5\"")))
                .andExpect(content().string(containsString("aria-invalid=\"true\"")))
                .andExpect(content().string(containsString("aria-describedby=\"loanAmount-hint loanAmount-error\"")))
                .andReturn();

        assertThat(idempotencyKeyIn(result)).isEqualTo(KEY);
        String body = result.getResponse().getContentAsString();
        assertThat(AUTOFOCUS_ATTRIBUTE.matcher(body).results().count()).as("exactly one element takes focus").isEqualTo(1);
        assertThat(body).containsPattern("id=\"loanAmount\"[^>]*autofocus");
        verify(quoteService, never()).requestQuote(any(), anyString());
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new QuoteFailure.VendorUnavailable("503", null), 503,
                        "The quote service is temporarily unavailable.", true),
                Arguments.of(new QuoteFailure.VendorTimeout("slow", null), 504,
                        "The quote service took too long to respond. Please try again.", true),
                Arguments.of(new QuoteFailure.VendorMisconfigured("401"), 502,
                        "because of a configuration problem on our side", false),
                Arguments.of(new QuoteFailure.VendorRejected("400"), 502,
                        "couldn&#39;t process these loan details", false),
                Arguments.of(new QuoteFailure.VendorBadResponse("bad", null), 502,
                        "We received an unexpected response from the quote service.", true),
                Arguments.of(new IllegalStateException("bug"), 500,
                        "Something went wrong on our side. Please try again.", true));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("failures")
    @DisplayName("each failure renders its status, message, reference and Try again rule, keeping the same key")
    void failureRendersAlert(RuntimeException failure, int expectedStatus, String expectedMessage,
                             boolean tryAgainShown) throws Exception {
        given(quoteService.requestQuote(any(), eq(KEY))).willThrow(failure);

        MvcResult result = mvc.perform(htmxPost("500000", "360", "MEDIUM", KEY)
                        .header("X-Correlation-Id", "ref-12345"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(containsString("role=\"alert\"")))
                .andExpect(content().string(containsString(expectedMessage)))
                .andExpect(content().string(containsString("ref-12345")))
                .andExpect(content().string(containsString("value=\"500000\"")))
                .andReturn();

        assertThat(idempotencyKeyIn(result)).as("Try again must not create a duplicate").isEqualTo(KEY);
        assertThat(result.getResponse().getContentAsString().contains(">Try again<")).isEqualTo(tryAgainShown);
    }

    @Test
    @DisplayName("without HX-Request, the POST returns the full page so the form works without JavaScript")
    void nonHtmxPostReturnsFullPage() throws Exception {
        given(quoteService.requestQuote(LOAN, KEY))
                .willReturn(new CommissionQuote("q-1", new BigDecimal("0.0060"), new BigDecimal("3000.00")));

        mvc.perform(post("/quotes").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("loanAmount", "500000").param("loanTermInMonths", "360").param("riskBand", "MEDIUM")
                        .param("idempotencyKey", KEY))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<!DOCTYPE html>")))
                .andExpect(content().string(containsString("$3,000.00")));
    }

    @Test
    @DisplayName("a missing or tampered idempotency key is replaced, never forwarded")
    void tamperedKeyIsReplaced() throws Exception {
        given(quoteService.requestQuote(eq(LOAN), anyString()))
                .willReturn(new CommissionQuote("q-1", new BigDecimal("0.0060"), new BigDecimal("3000.00")));

        mvc.perform(htmxPost("500000", "360", "MEDIUM", "<script>")).andExpect(status().isOk());

        verify(quoteService).requestQuote(eq(LOAN),
                argThat(key -> UUID.fromString(key) != null));
    }

    @Test
    @DisplayName("user input is escaped, never rendered as HTML")
    void escapesUserInput() throws Exception {
        mvc.perform(htmxPost("<script>alert(1)</script>", "12", "LOW", KEY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    @DisplayName("internal failure detail, such as an exception message, never reaches the page")
    void neverRendersExceptionMessages() throws Exception {
        given(quoteService.requestQuote(any(), anyString()))
                .willThrow(new QuoteFailure.VendorMisconfigured("api-key=super-secret-test-key rejected"));

        mvc.perform(htmxPost("500000", "360", "MEDIUM", KEY))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("super-secret-test-key"))));
    }

    private static MockHttpServletRequestBuilder htmxPost(String amount, String term, String band, String key) {
        MockHttpServletRequestBuilder request = post("/quotes")
                .header(QuoteController.HX_REQUEST_HEADER, "true")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("loanAmount", amount)
                .param("loanTermInMonths", term)
                .param("idempotencyKey", key);
        return band == null ? request : request.param("riskBand", band);
    }

    private static String idempotencyKeyIn(MvcResult result) throws Exception {
        Matcher matcher = HIDDEN_KEY.matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).as("hidden idempotency key field").isTrue();
        return matcher.group(1);
    }
}
