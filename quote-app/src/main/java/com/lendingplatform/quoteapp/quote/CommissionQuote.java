package com.lendingplatform.quoteapp.quote;

import java.math.BigDecimal;

/**
 * A quote from the vendor, after sanity checks. The app never calculates commission itself (assumption A8).
 *
 * @param commissionRate a fraction: 0.0060 means 0.60%
 * @param totalCommission AUD
 */
public record CommissionQuote(String quoteId, BigDecimal commissionRate, BigDecimal totalCommission) {
}
