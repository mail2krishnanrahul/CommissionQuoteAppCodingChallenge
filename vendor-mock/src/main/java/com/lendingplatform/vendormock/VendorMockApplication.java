package com.lendingplatform.vendormock;

import com.lendingplatform.vendormock.pricing.CommissionCalculator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Stand-in for the vendor's Commission Quote API, built from the agreed contract
 * ({@code contracts/commission-quote-api.yaml}) so the Quote App can be developed before the real API exists.
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
}
