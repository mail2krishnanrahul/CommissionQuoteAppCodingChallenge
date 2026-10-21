package com.lendingplatform.quoteapp.web;

import com.lendingplatform.quoteapp.quote.CommissionQuote;
import com.lendingplatform.quoteapp.quote.LoanDetails;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A quote formatted for display, so the template only prints strings. Formatting is the app's job; calculating
 * commission never is (assumption A8).
 */
public record QuoteResultView(
        String totalCommission,
        String commissionRate,
        String quoteId,
        String loanAmount,
        String loanTerm,
        String riskBand,
        String generatedAt) {

    private static final Locale AUSTRALIA = Locale.forLanguageTag("en-AU");
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final DateTimeFormatter GENERATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm:ss a z", AUSTRALIA);
    private static final int PERCENT_DECIMALS = 2;

    public static QuoteResultView of(CommissionQuote quote, LoanDetails loanDetails, Clock clock) {
        return new QuoteResultView(
                aud(quote.totalCommission()),
                percent(quote.commissionRate()),
                quote.quoteId(),
                aud(loanDetails.loanAmount()),
                loanDetails.loanTermInMonths() + " months",
                loanDetails.riskBand().label(),
                ZonedDateTime.now(clock.withZone(SYDNEY)).format(GENERATED_AT_FORMAT));
    }

    /** 3000.00 → "$3,000.00". */
    private static String aud(BigDecimal amount) {
        // A new instance per call: NumberFormat isn't thread-safe.
        NumberFormat currency = NumberFormat.getCurrencyInstance(AUSTRALIA);
        currency.setRoundingMode(RoundingMode.HALF_UP);
        return currency.format(amount);
    }

    /** 0.0060 (a fraction, assumption A3) → "0.60%". */
    private static String percent(BigDecimal fraction) {
        return fraction.movePointRight(2).setScale(PERCENT_DECIMALS, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
