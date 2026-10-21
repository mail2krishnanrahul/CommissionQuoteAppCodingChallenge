package com.lendingplatform.vendormock.chaos;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How flaky the mock is (ARCHITECTURE §12). Checked at startup so a typo like {@code failure-rate: 20} fails fast with
 * a clear message instead of making every call fail.
 *
 * @param failureRate probability that an authenticated call fails with a random 500 or 503
 * @param slowRate probability that a call waits {@code slowDelay} (longer than the client's timeout) and then succeeds
 * @param latencyMin lower bound of the normal latency jitter
 * @param latencyMax upper bound of the normal latency jitter
 * @param scenarioHeaderEnabled whether {@code X-Mock-Scenario: ok|error|slow} may force an outcome
 */
@ConfigurationProperties(prefix = "mock.chaos")
public record ChaosProperties(
        boolean enabled,
        double failureRate,
        double slowRate,
        Duration slowDelay,
        Duration latencyMin,
        Duration latencyMax,
        boolean scenarioHeaderEnabled) {

    public ChaosProperties {
        requireProbability("mock.chaos.failure-rate", failureRate);
        requireProbability("mock.chaos.slow-rate", slowRate);
        if (failureRate + slowRate > 1.0) {
            throw new IllegalArgumentException("mock.chaos.failure-rate + mock.chaos.slow-rate must be at most 1, but was "
                    + (failureRate + slowRate));
        }
        requirePositive("mock.chaos.slow-delay", slowDelay);
        requireNonNegative("mock.chaos.latency-min", latencyMin);
        requireNonNegative("mock.chaos.latency-max", latencyMax);
        if (latencyMin.compareTo(latencyMax) > 0) {
            throw new IllegalArgumentException("mock.chaos.latency-min (" + latencyMin
                    + ") must not be greater than mock.chaos.latency-max (" + latencyMax + ")");
        }
    }

    private static void requireProbability(String name, double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, but was " + value);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration, but was " + value);
        }
    }

    private static void requireNonNegative(String name, Duration value) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be zero or a positive duration, but was " + value);
        }
    }
}
