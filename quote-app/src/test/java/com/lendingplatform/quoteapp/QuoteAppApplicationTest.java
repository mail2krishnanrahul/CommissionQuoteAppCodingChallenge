package com.lendingplatform.quoteapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lendingplatform.quoteapp.vendor.VendorClientProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The API key is required configuration: a missing key must stop startup, not fail the first quote (§9.1). */
class QuoteAppApplicationTest {

    @Test
    @DisplayName("refuses to start without vendor.api-key (VENDOR_API_KEY) outside the local profile")
    void failsToStartWithoutApiKey() {
        assertThatThrownBy(() -> start("--vendor.api-key="))
                .hasStackTraceContaining("apiKey");
    }

    @Test
    @DisplayName("starts when the key is provided")
    void startsWithApiKey() {
        try (ConfigurableApplicationContext context = start("--vendor.api-key=a-real-looking-key")) {
            assertThat(context.getBean(VendorClientProperties.class).apiKey()).isEqualTo("a-real-looking-key");
        }
    }

    @Test
    @DisplayName("the local profile supplies the obviously fake key, so a local run needs no configuration")
    void localProfileSuppliesFakeKey() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=local")) {
            assertThat(context.getBean(VendorClientProperties.class).apiKey()).isEqualTo("local-dev-key-not-a-secret");
        }
    }

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(QuoteAppApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
