package com.lendingplatform.vendormock;

import com.lendingplatform.vendormock.api.QuoteResponse;
import com.lendingplatform.vendormock.chaos.Sleeper;
import com.lendingplatform.vendormock.idempotency.IdempotencyProperties;
import com.lendingplatform.vendormock.idempotency.IdempotencyStore;
import com.lendingplatform.vendormock.pricing.CommissionCalculator;
import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Stand-in for the vendor's Commission Quote API, built from the agreed contract
 * ({@code contracts/commission-quote-api.yaml}) so the Quote App can be developed before the real API exists.
 *
 * <p>Randomness, waiting and time are beans so tests can replace them (ADR-008).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VendorMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(VendorMockApplication.class, args);
    }

    @Bean
    CommissionCalculator commissionCalculator() {
        return new CommissionCalculator();
    }

    @Bean
    IdempotencyStore<QuoteResponse> idempotencyStore(IdempotencyProperties properties, Clock clock) {
        return new IdempotencyStore<>(properties, clock);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RandomGenerator randomGenerator() {
        // java.util.Random is thread-safe, unlike most RandomGenerator implementations.
        return new Random();
    }

    @Bean
    Sleeper sleeper() {
        return Sleeper.threadSleeper();
    }
}
