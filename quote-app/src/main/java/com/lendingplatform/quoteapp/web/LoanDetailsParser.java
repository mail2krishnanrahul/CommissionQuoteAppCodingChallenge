package com.lendingplatform.quoteapp.web;

import com.lendingplatform.quoteapp.quote.LoanDetails;
import com.lendingplatform.quoteapp.quote.RiskBand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The single authority for turning raw form input into valid {@link LoanDetails} or field errors with messages a
 * person can act on (ARCHITECTURE §6.1). Browser-side constraints are only hints.
 *
 * <p>Traps this closes: {@code new BigDecimal("1e6")} succeeds, so the input must match a plain-number shape first;
 * stripping every comma would turn the European {@code 12,34} into 1234, so commas are only accepted as thousands
 * separators in groups of three.
 */
@Component
public class LoanDetailsParser {

    public static final String LOAN_AMOUNT = "loanAmount";
    public static final String LOAN_TERM = "loanTermInMonths";
    public static final String RISK_BAND = "riskBand";

    static final String AMOUNT_REQUIRED = "Enter a loan amount.";
    static final String AMOUNT_NOT_A_NUMBER = "Enter the loan amount as a number, for example 250000 or 250,000.";
    static final String AMOUNT_TOO_PRECISE = "Loan amount can have at most 2 decimal places.";
    static final String AMOUNT_OUT_OF_RANGE = "Enter a loan amount between $1,000 and $5,000,000.";
    static final String TERM_REQUIRED = "Enter a loan term in months.";
    static final String TERM_NOT_WHOLE = "Enter the loan term as a whole number of months.";
    static final String TERM_OUT_OF_RANGE = "Enter a loan term between 12 and 360 months.";
    static final String RISK_BAND_REQUIRED = "Select a risk band.";

    private static final BigDecimal MIN_AMOUNT = new BigDecimal("1000.00");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("5000000.00");
    private static final int MAX_AMOUNT_DECIMALS = 2;
    private static final int MAX_AMOUNT_LENGTH = 20;
    private static final int MIN_TERM = 12;
    private static final int MAX_TERM = 360;

    /** Optional minus (so negatives get the range message), optional $, digits with optional thousands groups, decimals. */
    private static final Pattern AMOUNT_SHAPE = Pattern.compile("^(-)?\\$?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?$");
    /** At most 3 digits, so the value always fits an int and absurd input never reaches Integer.parseInt. */
    private static final Pattern TERM_SHAPE = Pattern.compile("^\\d{1,3}$");

    public sealed interface Result {

        record Valid(LoanDetails loanDetails) implements Result {
        }

        /** @param fieldErrors field name → message, in form order, so the first entry is the first invalid field */
        record Invalid(SequencedMap<String, String> fieldErrors) implements Result {
        }
    }

    public Result parse(QuoteForm form) {
        SequencedMap<String, String> errors = new LinkedHashMap<>();
        Optional<BigDecimal> loanAmount = parseLoanAmount(form.loanAmount(), errors);
        Optional<Integer> loanTerm = parseLoanTerm(form.loanTermInMonths(), errors);
        Optional<RiskBand> riskBand = parseRiskBand(form.riskBand(), errors);

        if (!errors.isEmpty()) {
            return new Result.Invalid(Collections.unmodifiableSequencedMap(errors));
        }
        return new Result.Valid(new LoanDetails(loanAmount.orElseThrow(), loanTerm.orElseThrow(), riskBand.orElseThrow()));
    }

    private static Optional<BigDecimal> parseLoanAmount(String raw, Map<String, String> errors) {
        String input = raw == null ? "" : raw.strip();
        if (input.isEmpty()) {
            return reject(errors, LOAN_AMOUNT, AMOUNT_REQUIRED);
        }
        Matcher matcher = AMOUNT_SHAPE.matcher(input);
        if (input.length() > MAX_AMOUNT_LENGTH || !matcher.matches()) {
            return reject(errors, LOAN_AMOUNT, AMOUNT_NOT_A_NUMBER);
        }
        String decimals = matcher.group(3);
        if (decimals != null && decimals.length() > MAX_AMOUNT_DECIMALS) {
            return reject(errors, LOAN_AMOUNT, AMOUNT_TOO_PRECISE);
        }

        String digits = matcher.group(2).replace(",", "") + (decimals == null ? "" : "." + decimals);
        BigDecimal amount = new BigDecimal(digits).setScale(MAX_AMOUNT_DECIMALS, RoundingMode.UNNECESSARY);
        if (matcher.group(1) != null) {
            amount = amount.negate();
        }
        if (amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            return reject(errors, LOAN_AMOUNT, AMOUNT_OUT_OF_RANGE);
        }
        return Optional.of(amount);
    }

    private static Optional<Integer> parseLoanTerm(String raw, Map<String, String> errors) {
        String input = raw == null ? "" : raw.strip();
        if (input.isEmpty()) {
            return reject(errors, LOAN_TERM, TERM_REQUIRED);
        }
        if (!TERM_SHAPE.matcher(input).matches()) {
            return reject(errors, LOAN_TERM, TERM_NOT_WHOLE);
        }
        int months = Integer.parseInt(input);
        if (months < MIN_TERM || months > MAX_TERM) {
            return reject(errors, LOAN_TERM, TERM_OUT_OF_RANGE);
        }
        return Optional.of(months);
    }

    private static Optional<RiskBand> parseRiskBand(String raw, Map<String, String> errors) {
        // Exact match only: the radios post exact names, so anything else was tampered with.
        Optional<RiskBand> band = Arrays.stream(RiskBand.values())
                .filter(candidate -> candidate.name().equals(raw))
                .findFirst();
        if (band.isEmpty()) {
            errors.put(RISK_BAND, RISK_BAND_REQUIRED);
        }
        return band;
    }

    private static <T> Optional<T> reject(Map<String, String> errors, String field, String message) {
        errors.put(field, message);
        return Optional.empty();
    }
}
