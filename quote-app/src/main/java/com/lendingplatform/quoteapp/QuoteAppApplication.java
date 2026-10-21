package com.lendingplatform.quoteapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Staff-facing web app: captures loan details and shows a commission quote from the vendor's Commission Quote API.
 * The browser only ever talks to this app; it holds the vendor API key (ADR-001).
 */
@SpringBootApplication
public class QuoteAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuoteAppApplication.class, args);
    }
}
