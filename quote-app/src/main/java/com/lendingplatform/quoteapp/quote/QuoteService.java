package com.lendingplatform.quoteapp.quote;

import org.springframework.stereotype.Service;

/**
 * The application seam between the web layer and the vendor. Thin today on purpose: it is where the likely next
 * requirements (quote history, audit, comparing vendors) land, so the controller stays about HTTP.
 */
@Service
public class QuoteService {

    private final CommissionQuoteGateway gateway;

    public QuoteService(CommissionQuoteGateway gateway) {
        this.gateway = gateway;
    }

    /** @throws QuoteFailure if no quote could be obtained */
    public CommissionQuote requestQuote(LoanDetails loanDetails, String idempotencyKey) {
        return gateway.requestQuote(loanDetails, idempotencyKey);
    }
}
