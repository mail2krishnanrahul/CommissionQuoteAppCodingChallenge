package com.lendingplatform.vendormock.api;

import com.lendingplatform.vendormock.idempotency.IdempotencyStore;
import com.lendingplatform.vendormock.pricing.CommissionCalculator;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /v1/commission-quotes}. By the time a request gets here it is authenticated and past chaos. */
@RestController
public class CommissionQuoteController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final CommissionCalculator calculator;
    private final IdempotencyStore<QuoteResponse> idempotencyStore;

    public CommissionQuoteController(CommissionCalculator calculator, IdempotencyStore<QuoteResponse> idempotencyStore) {
        this.calculator = calculator;
        this.idempotencyStore = idempotencyStore;
    }

    @PostMapping(path = "/v1/commission-quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QuoteResponse> createQuote(
            @Valid @RequestBody QuoteRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        // The key is an optional extension (assumption A6): without one, every call is a new quote.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.ok(price(request));
        }

        IdempotencyStore.Lookup<QuoteResponse> lookup =
                idempotencyStore.findOrCreate(idempotencyKey, request.fingerprint(), () -> price(request));
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (lookup.replayed()) {
            ok.header(REPLAYED_HEADER, "true");
        }
        return ok.body(lookup.response());
    }

    private QuoteResponse price(QuoteRequest request) {
        CommissionCalculator.Commission commission =
                calculator.calculate(request.loanAmount(), request.loanTermInMonths(), request.riskBand());
        return new QuoteResponse(UUID.randomUUID().toString(), commission.commissionRate(), commission.totalCommission());
    }
}
