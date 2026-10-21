package com.lendingplatform.vendormock.api;

import java.math.BigDecimal;

/** Response body from the contract. {@code commissionRate} is a fraction: 0.0060 means 0.60% (assumption A3). */
public record QuoteResponse(String quoteId, BigDecimal commissionRate, BigDecimal totalCommission) {
}
