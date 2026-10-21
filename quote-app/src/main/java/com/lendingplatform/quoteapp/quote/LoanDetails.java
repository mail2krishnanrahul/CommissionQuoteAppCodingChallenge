package com.lendingplatform.quoteapp.quote;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Loan details that have already passed input parsing, so everything downstream can trust them.
 *
 * @param loanAmount AUD, 1,000.00 to 5,000,000.00, at most 2 decimal places
 * @param loanTermInMonths whole months, 12 to 360
 */
public record LoanDetails(BigDecimal loanAmount, int loanTermInMonths, RiskBand riskBand) {

    public LoanDetails {
        Objects.requireNonNull(loanAmount, "loanAmount");
        Objects.requireNonNull(riskBand, "riskBand");
    }
}
