package com.lendingplatform.vendormock.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The mock's illustrative commission rules (ARCHITECTURE §6.2). The real rules belong to the vendor (assumption A8).
 *
 * <p>A pure class with no Spring dependencies, so the worked examples are plain unit tests. Rates are held as integer
 * basis points and converted once, so the rate itself never carries rounding error (ADR-004).
 */
public final class CommissionCalculator {

    private static final int TERM_UPLIFT_THRESHOLD_MONTHS = 240;
    private static final int TERM_UPLIFT_BPS = 5;
    private static final BigDecimal MAX_TOTAL_COMMISSION = new BigDecimal("25000.00");
    private static final BigDecimal BPS_PER_UNIT = BigDecimal.valueOf(10_000);
    private static final int RATE_SCALE = 4;
    private static final int MONEY_SCALE = 2;

    public record Commission(BigDecimal commissionRate, BigDecimal totalCommission) {
    }

    public Commission calculate(BigDecimal loanAmount, int loanTermInMonths, RiskBand riskBand) {
        BigDecimal commissionRate = toRate(rateInBps(loanTermInMonths, riskBand));
        // HALF_UP matches "round to the nearest cent" as people expect it; 1,090.00 × 0.0065 = 7.085 → 7.09.
        BigDecimal uncapped = loanAmount.multiply(commissionRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new Commission(commissionRate, uncapped.min(MAX_TOTAL_COMMISSION));
    }

    private static int rateInBps(int loanTermInMonths, RiskBand riskBand) {
        int uplift = loanTermInMonths >= TERM_UPLIFT_THRESHOLD_MONTHS ? TERM_UPLIFT_BPS : 0;
        return riskBand.baseRateBps() + uplift;
    }

    private static BigDecimal toRate(int bps) {
        // Exact: a whole number of basis points always fits in 4 decimal places.
        return BigDecimal.valueOf(bps).divide(BPS_PER_UNIT, RATE_SCALE, RoundingMode.UNNECESSARY);
    }
}
