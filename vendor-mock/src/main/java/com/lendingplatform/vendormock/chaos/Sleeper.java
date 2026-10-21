package com.lendingplatform.vendormock.chaos;

import java.time.Duration;

/** Waiting, behind an interface, so tests can record a "sleep" instead of taking one (ADR-008). */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);

    static Sleeper threadSleeper() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                // Restore the flag so the container can see the request thread was interrupted.
                Thread.currentThread().interrupt();
            }
        };
    }
}
