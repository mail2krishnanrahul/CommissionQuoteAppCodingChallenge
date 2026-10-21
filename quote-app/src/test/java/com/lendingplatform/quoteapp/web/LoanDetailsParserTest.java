package com.lendingplatform.quoteapp.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.lendingplatform.quoteapp.quote.LoanDetails;
import com.lendingplatform.quoteapp.quote.RiskBand;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** ARCHITECTURE §6.1, row by row. Every rejection asserts the exact message the user sees. */
class LoanDetailsParserTest {

    private static final String VALID_AMOUNT = "500000";
    private static final String VALID_TERM = "360";
    private static final String VALID_BAND = "MEDIUM";

    private final LoanDetailsParser parser = new LoanDetailsParser();

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            250000          | 250000.00
            250,000         | 250000.00
            $250,000.50     | 250000.50
            ` 1000 `        | 1000.00
            1000            | 1000.00
            5000000         | 5000000.00
            5,000,000.00    | 5000000.00
            1234567.8       | 1234567.80
            $1,090          | 1090.00
            """)
    @DisplayName("accepts plain amounts, thousands commas, a leading $ and surrounding spaces")
    void acceptsFriendlyAmounts(String raw, String expected) {
        LoanDetails loan = parseValid(raw, VALID_TERM, VALID_BAND);

        assertThat(loan.loanAmount()).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("a blank amount asks for one")
    void blankAmount(String raw) {
        assertFieldError(raw, VALID_TERM, VALID_BAND, "loanAmount", "Enter a loan amount.");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "abc", "12abc", "1e6", "1E6", "NaN", "Infinity", "1,00,000", "12,34", "1,0000", ",100", "100,",
            "1.", ".5", "$$100", "1 000", "１０００", "123456789012345678901"
    })
    @DisplayName("anything that isn't a plain number is rejected, including scientific notation and non-thousands commas")
    void notAPlainNumber(String raw) {
        assertFieldError(raw, VALID_TERM, VALID_BAND, "loanAmount",
                "Enter the loan amount as a number, for example 250000 or 250,000.");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"1000.001", "250,000.505"})
    @DisplayName("more than 2 decimal places is rejected with its own message")
    void tooManyDecimals(String raw) {
        assertFieldError(raw, VALID_TERM, VALID_BAND, "loanAmount", "Loan amount can have at most 2 decimal places.");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"0", "-1000", "-$1,000", "999.99", "5000000.01", "99999999999999999999"})
    @DisplayName("zero, negatives and anything outside $1,000–$5,000,000 are out of range")
    void amountOutOfRange(String raw) {
        assertFieldError(raw, VALID_TERM, VALID_BAND, "loanAmount", "Enter a loan amount between $1,000 and $5,000,000.");
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({"12, 12", "360, 360", "' 120 ', 120", "012, 12"})
    @DisplayName("accepts whole months from 12 to 360")
    void acceptsTerms(String raw, int expected) {
        assertThat(parseValid(VALID_AMOUNT, raw, VALID_BAND).loanTermInMonths()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("a blank term asks for one")
    void blankTerm(String raw) {
        assertFieldError(VALID_AMOUNT, raw, VALID_BAND, "loanTermInMonths", "Enter a loan term in months.");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"12.5", "12.0", "abc", "-12", "1e2", "0360", "12 months"})
    @DisplayName("a term that isn't a whole number of months is rejected")
    void termNotWhole(String raw) {
        assertFieldError(VALID_AMOUNT, raw, VALID_BAND, "loanTermInMonths",
                "Enter the loan term as a whole number of months.");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"0", "11", "361", "999"})
    @DisplayName("a term outside 12–360 is out of range")
    void termOutOfRange(String raw) {
        assertFieldError(VALID_AMOUNT, raw, VALID_BAND, "loanTermInMonths",
                "Enter a loan term between 12 and 360 months.");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource({"LOW, LOW", "MEDIUM, MEDIUM", "HIGH, HIGH"})
    @DisplayName("accepts each risk band")
    void acceptsRiskBands(String raw, RiskBand expected) {
        assertThat(parseValid(VALID_AMOUNT, VALID_TERM, raw).riskBand()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"low", "EXTREME", "0", "MEDIUM "})
    @DisplayName("a missing or tampered risk band is a field error, not a 500")
    void invalidRiskBand(String raw) {
        assertFieldError(VALID_AMOUNT, VALID_TERM, raw, "riskBand", "Select a risk band.");
    }

    @Test
    @DisplayName("reports every invalid field at once, in form order")
    void reportsAllErrorsInFormOrder() {
        LoanDetailsParser.Result result = parser.parse(new QuoteForm("12abc", "12.5", null, "key"));

        assertThat(result).isInstanceOfSatisfying(LoanDetailsParser.Result.Invalid.class, invalid ->
                assertThat(invalid.fieldErrors()).containsExactly(
                        entry("loanAmount",
                                "Enter the loan amount as a number, for example 250000 or 250,000."),
                        entry("loanTermInMonths",
                                "Enter the loan term as a whole number of months."),
                        entry("riskBand", "Select a risk band.")));
    }

    private LoanDetails parseValid(String amount, String term, String band) {
        LoanDetailsParser.Result result = parser.parse(new QuoteForm(amount, term, band, "key"));
        assertThat(result).isInstanceOf(LoanDetailsParser.Result.Valid.class);
        return ((LoanDetailsParser.Result.Valid) result).loanDetails();
    }

    private void assertFieldError(String amount, String term, String band, String field, String message) {
        LoanDetailsParser.Result result = parser.parse(new QuoteForm(amount, term, band, "key"));

        assertThat(result).isInstanceOfSatisfying(LoanDetailsParser.Result.Invalid.class, invalid ->
                assertThat(invalid.fieldErrors()).containsExactly(entry(field, message)));
    }
}
