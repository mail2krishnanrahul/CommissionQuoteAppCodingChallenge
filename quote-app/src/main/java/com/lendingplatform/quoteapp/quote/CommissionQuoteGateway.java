package com.lendingplatform.quoteapp.quote;

/**
 * Port to whatever calculates commission quotes. Today that is the vendor's HTTP API; keeping it behind an interface
 * means switching vendors, or adding a second one, changes only the {@code vendor} package (ARCHITECTURE §3.1).
 */
public interface CommissionQuoteGateway {

    /**
     * @param idempotencyKey the same key for every attempt and every "Try again", so the vendor never creates a
     *                       duplicate quote
     * @throws QuoteFailure if no quote could be obtained
     */
    CommissionQuote requestQuote(LoanDetails loanDetails, String idempotencyKey);
}
