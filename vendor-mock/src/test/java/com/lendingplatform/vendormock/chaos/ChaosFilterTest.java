package com.lendingplatform.vendormock.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.lendingplatform.vendormock.web.ProblemResponseWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The chaos filter with randomness and sleeping replaced by stubs, so every branch is exact (ADR-008). */
class ChaosFilterTest {

    private static final Duration SLOW_DELAY = Duration.ofSeconds(5);
    private static final Duration LATENCY_MIN = Duration.ofMillis(50);
    private static final Duration LATENCY_MAX = Duration.ofMillis(400);

    /** failure-rate 0.2 and slow-rate 0.05: rolls below 0.2 fail, rolls in [0.2, 0.25) are slow, the rest succeed. */
    private static final double FAILING_ROLL = 0.1;
    private static final double SLOW_ROLL = 0.22;
    private static final double OK_ROLL = 0.9;

    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper recordingSleeper = sleeps::add;
    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @ParameterizedTest(name = "coin {0} → {1} {2}")
    @CsvSource({
            "true,  503, SIMULATED_UNAVAILABLE",
            "false, 500, SIMULATED_INTERNAL_ERROR"
    })
    @DisplayName("a roll below failure-rate returns a 500 or 503 Problem Details and never reaches the controller")
    void failureRollReturnsProblem(boolean coin, int expectedStatus, String expectedCode) throws Exception {
        filter(defaults(), new StubRandom(FAILING_ROLL, coin)).doFilter(apiRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"" + expectedCode + "\"");
        assertThat(chain.getRequest()).as("controller must not run").isNull();
    }

    @Test
    @DisplayName("a roll in the slow band waits slow-delay, then carries on normally")
    void slowRollSleepsThenContinues() throws Exception {
        filter(defaults(), new StubRandom(SLOW_ROLL, false)).doFilter(apiRequest(), response, chain);

        assertThat(sleeps).containsExactly(SLOW_DELAY);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("otherwise the call gets small latency jitter within the configured bounds and carries on")
    void okRollAddsJitter() throws Exception {
        filter(defaults(), new StubRandom(OK_ROLL, false)).doFilter(apiRequest(), response, chain);

        assertThat(sleeps).singleElement().satisfies(sleep -> assertThat(sleep).isBetween(LATENCY_MIN, LATENCY_MAX));
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("when disabled, every call passes straight through with no delay")
    void disabledPassesThrough() throws Exception {
        ChaosProperties disabled = new ChaosProperties(false, 1.0, 0.0, SLOW_DELAY, LATENCY_MIN, LATENCY_MAX, true);

        filter(disabled, new StubRandom(FAILING_ROLL, true)).doFilter(apiRequest(), response, chain);

        assertThat(sleeps).isEmpty();
        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest(name = "X-Mock-Scenario: {0}")
    @CsvSource({"error", "ERROR"})
    @DisplayName("the scenario header forces an error even when the roll would succeed")
    void scenarioHeaderForcesError(String scenario) throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader(ChaosFilter.SCENARIO_HEADER, scenario);

        filter(defaults(), new StubRandom(OK_ROLL, true)).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("the scenario header forces a slow call")
    void scenarioHeaderForcesSlow() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader(ChaosFilter.SCENARIO_HEADER, "slow");

        filter(defaults(), new StubRandom(OK_ROLL, false)).doFilter(request, response, chain);

        assertThat(sleeps).containsExactly(SLOW_DELAY);
    }

    @Test
    @DisplayName("the scenario header forces success even when the roll would fail")
    void scenarioHeaderForcesOk() throws Exception {
        MockHttpServletRequest request = apiRequest();
        request.addHeader(ChaosFilter.SCENARIO_HEADER, "ok");

        filter(defaults(), new StubRandom(FAILING_ROLL, true)).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("the scenario header is ignored when scenario-header-enabled is false")
    void scenarioHeaderIgnoredWhenSwitchedOff() throws Exception {
        ChaosProperties headerOff = new ChaosProperties(true, 0.2, 0.05, SLOW_DELAY, LATENCY_MIN, LATENCY_MAX, false);
        MockHttpServletRequest request = apiRequest();
        request.addHeader(ChaosFilter.SCENARIO_HEADER, "error");

        filter(headerOff, new StubRandom(OK_ROLL, true)).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("paths outside the API, such as health, are never affected")
    void healthIsUnaffected() throws Exception {
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");

        filter(defaults(), new StubRandom(FAILING_ROLL, true)).doFilter(health, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(sleeps).isEmpty();
    }

    @ParameterizedTest(name = "failure-rate={0}, slow-rate={1}")
    @CsvSource({"1.5, 0.0", "-0.1, 0.0", "0.0, 1.1", "0.8, 0.3"})
    @DisplayName("out-of-range rates fail fast at startup with a clear message")
    void rejectsInvalidRates(double failureRate, double slowRate) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChaosProperties(true, failureRate, slowRate, SLOW_DELAY,
                        LATENCY_MIN, LATENCY_MAX, true))
                .withMessageContaining("mock.chaos.");
    }

    @Test
    @DisplayName("latency-min greater than latency-max fails fast")
    void rejectsInvertedLatencyBounds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChaosProperties(true, 0.2, 0.05, SLOW_DELAY, LATENCY_MAX, LATENCY_MIN, true))
                .withMessageContaining("latency-min");
    }

    private static ChaosProperties defaults() {
        return new ChaosProperties(true, 0.2, 0.05, SLOW_DELAY, LATENCY_MIN, LATENCY_MAX, true);
    }

    private ChaosFilter filter(ChaosProperties properties, RandomGenerator random) {
        return new ChaosFilter(properties, random, recordingSleeper,
                new ProblemResponseWriter(JsonMapper.builder().build()));
    }

    private static MockHttpServletRequest apiRequest() {
        return new MockHttpServletRequest("POST", "/v1/commission-quotes");
    }

    /** Returns a fixed roll and coin flip; jitter picks the middle of the range. */
    private record StubRandom(double roll, boolean coin) implements RandomGenerator {

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("not used by ChaosFilter");
        }

        @Override
        public double nextDouble() {
            return roll;
        }

        @Override
        public boolean nextBoolean() {
            return coin;
        }

        @Override
        public long nextLong(long bound) {
            return bound / 2;
        }
    }
}
