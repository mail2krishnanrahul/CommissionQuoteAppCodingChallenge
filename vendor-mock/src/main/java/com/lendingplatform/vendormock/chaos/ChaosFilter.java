package com.lendingplatform.vendormock.chaos;

import com.lendingplatform.vendormock.security.ApiKeyFilter;
import com.lendingplatform.vendormock.web.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the mock behave like a flaky network service: a random 500/503, a response slower than the client's timeout,
 * or normal latency jitter.
 *
 * <p>Runs after {@link ApiKeyFilter}, so chaos only ever applies to authenticated calls; a missing key is always a 401.
 * A failure here happens <em>before</em> the quote is processed, but a slow call still completes after the client has
 * given up, which is why the client's retry carries the same Idempotency-Key.
 *
 * <p>Randomness and waiting are injected ({@link RandomGenerator}, {@link Sleeper}) so tests are exact (ADR-008).
 */
@Component
@Order(ChaosFilter.ORDER)
public class ChaosFilter extends OncePerRequestFilter {

    public static final int ORDER = ApiKeyFilter.ORDER + 10;
    public static final String SCENARIO_HEADER = "X-Mock-Scenario";

    private static final Logger log = LoggerFactory.getLogger(ChaosFilter.class);
    private static final String API_PATH_PREFIX = "/v1/";

    enum Outcome { OK, ERROR, SLOW }

    private final ChaosProperties properties;
    private final RandomGenerator random;
    private final Sleeper sleeper;
    private final ProblemResponseWriter problems;

    public ChaosFilter(ChaosProperties properties, RandomGenerator random, Sleeper sleeper,
                       ProblemResponseWriter problems) {
        this.properties = properties;
        this.random = random;
        this.sleeper = sleeper;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (chooseOutcome(request)) {
            case ERROR -> injectFailure(request, response);
            case SLOW -> {
                log.info("chaos.slow delayMs={}", properties.slowDelay().toMillis());
                sleeper.sleep(properties.slowDelay());
                chain.doFilter(request, response);
            }
            case OK -> {
                if (properties.enabled()) {
                    sleeper.sleep(jitter());
                }
                chain.doFilter(request, response);
            }
        }
    }

    private Outcome chooseOutcome(HttpServletRequest request) {
        Optional<Outcome> forced = forcedOutcome(request);
        if (forced.isPresent()) {
            return forced.get();
        }
        if (!properties.enabled()) {
            return Outcome.OK;
        }
        double roll = random.nextDouble();
        if (roll < properties.failureRate()) {
            return Outcome.ERROR;
        }
        if (roll < properties.failureRate() + properties.slowRate()) {
            return Outcome.SLOW;
        }
        return Outcome.OK;
    }

    private Optional<Outcome> forcedOutcome(HttpServletRequest request) {
        String scenario = request.getHeader(SCENARIO_HEADER);
        if (!properties.scenarioHeaderEnabled() || scenario == null) {
            return Optional.empty();
        }
        return switch (scenario.strip().toLowerCase(Locale.ROOT)) {
            case "ok" -> Optional.of(Outcome.OK);
            case "error" -> Optional.of(Outcome.ERROR);
            case "slow" -> Optional.of(Outcome.SLOW);
            default -> Optional.empty();
        };
    }

    private void injectFailure(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (random.nextBoolean()) {
            log.warn("chaos.failure status=503");
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "SIMULATED_UNAVAILABLE",
                    "Simulated temporary unavailability. Safe to retry with the same Idempotency-Key.");
        } else {
            log.warn("chaos.failure status=500");
            problems.write(request, response, HttpStatus.INTERNAL_SERVER_ERROR, "SIMULATED_INTERNAL_ERROR",
                    "Simulated internal error. Safe to retry with the same Idempotency-Key.");
        }
    }

    private Duration jitter() {
        long minMs = properties.latencyMin().toMillis();
        long spreadMs = properties.latencyMax().toMillis() - minMs;
        return Duration.ofMillis(minMs + random.nextLong(spreadMs + 1));
    }
}
