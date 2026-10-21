package com.lendingplatform.quoteapp.web;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Time is a bean so tests can fix "Generated at" (ARCHITECTURE §13). */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
