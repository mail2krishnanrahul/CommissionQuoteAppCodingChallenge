package com.lendingplatform.vendormock.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The ARCHITECTURE §6.2 worked examples, row by row. */
class CommissionCalculatorTest {

    private final CommissionCalculator calculator = new CommissionCalculator();

    @ParameterizedTest(name = "{0} / {1} months / {2} → {3} and {4} ({5})")
    @CsvSource(textBlock = """
            500000.00,  360, MEDIUM, 0.0060,  3000.00, term uplift applies
            123456.78,  120, MEDIUM, 0.0055,   679.01, rounds down (679.01229)
            1090.00,     12, LOW,    0.0065,     7.09, exact half 7.085 rounds HALF_UP (HALF_EVEN would give 7.08)
            5000000.00, 300, LOW,    0.0070, 25000.00, capped (uncapped 35000.00)
            1000.00,     12, HIGH,   0.0040,     4.00, minimum amount
            250000.00,  239, HIGH,   0.0040,  1000.00, one month below the uplift boundary
            250000.00,  240, HIGH,   0.0045,  1125.00, on the uplift boundary
            """)
    @DisplayName("calculates the rate and total from the worked examples")
    void calculatesWorkedExamples(String loanAmount, int loanTermInMonths, RiskBand riskBand,
                                  String expectedRate, String expectedTotal, String whatItProves) {
        CommissionCalculator.Commission commission =
                calculator.calculate(new BigDecimal(loanAmount), loanTermInMonths, riskBand);

        // isEqualTo (not isEqualByComparingTo) so the scale is part of the contract: 0.0060, not 0.006.
        assertThat(commission.commissionRate()).isEqualTo(new BigDecimal(expectedRate));
        assertThat(commission.totalCommission()).isEqualTo(new BigDecimal(expectedTotal));
    }

    @Test
    @DisplayName("an amount sent without cents still produces a total at 2 decimal places")
    void wholeDollarAmountProducesCentsScale() {
        CommissionCalculator.Commission commission =
                calculator.calculate(new BigDecimal("500000"), 360, RiskBand.MEDIUM);

        assertThat(commission.totalCommission()).isEqualTo(new BigDecimal("3000.00"));
    }
}
