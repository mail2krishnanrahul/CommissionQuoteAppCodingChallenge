package com.lendingplatform.quoteapp.vendor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * The response body on the wire. A tolerant reader: fields the vendor adds later are ignored, so they can't break us
 * (ADR-011). Whether the values make sense is checked by the gateway, not here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record VendorQuoteResponse(String quoteId, BigDecimal commissionRate, BigDecimal totalCommission) {
}
