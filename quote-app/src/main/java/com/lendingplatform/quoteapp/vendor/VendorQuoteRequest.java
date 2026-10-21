package com.lendingplatform.quoteapp.vendor;

import com.lendingplatform.quoteapp.quote.LoanDetails;
import java.math.BigDecimal;

/** The request body on the wire, exactly as the contract names it. Kept separate from the domain {@link LoanDetails}. */
record VendorQuoteRequest(BigDecimal loanAmount, int loanTermInMonths, String riskBand) {

    static VendorQuoteRequest from(LoanDetails loanDetails) {
        return new VendorQuoteRequest(
                loanDetails.loanAmount(), loanDetails.loanTermInMonths(), loanDetails.riskBand().name());
    }
}
