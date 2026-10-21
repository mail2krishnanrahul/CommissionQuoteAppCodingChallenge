package com.lendingplatform.vendormock.api;

import com.lendingplatform.vendormock.pricing.RiskBand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body from the contract. Boxed types so a missing field is a validation error, not a silent zero.
 * Type strictness (no "12" for a number, no 12.5 for the term, no unknown fields) is Jackson config in application.yml.
 */
public record QuoteRequest(
        @NotNull(message = "is required")
        @DecimalMin(value = "1000.00", message = "must be between 1000.00 and 5000000.00")
        @DecimalMax(value = "5000000.00", message = "must be between 1000.00 and 5000000.00")
        @Digits(integer = 7, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal loanAmount,

        @NotNull(message = "is required")
        @Min(value = 12, message = "must be a whole number between 12 and 360")
        @Max(value = 360, message = "must be a whole number between 12 and 360")
        Integer loanTermInMonths,

        @NotNull(message = "is required")
        RiskBand riskBand) {
}
