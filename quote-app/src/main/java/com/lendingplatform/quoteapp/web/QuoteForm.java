package com.lendingplatform.quoteapp.web;

/**
 * Exactly what the browser posted, as raw strings, so the form can be re-rendered with the user's own input and the
 * parser can explain precisely what is wrong with it (ADR-010).
 *
 * @param idempotencyKey from a hidden field; see ARCHITECTURE §10.3 for when it changes
 */
public record QuoteForm(String loanAmount, String loanTermInMonths, String riskBand, String idempotencyKey) {

    public static QuoteForm empty(String idempotencyKey) {
        return new QuoteForm("", "", null, idempotencyKey);
    }

    public QuoteForm withIdempotencyKey(String newKey) {
        return new QuoteForm(loanAmount, loanTermInMonths, riskBand, newKey);
    }
}
