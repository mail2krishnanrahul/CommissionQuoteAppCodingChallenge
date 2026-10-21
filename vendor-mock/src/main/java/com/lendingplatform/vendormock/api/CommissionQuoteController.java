package com.lendingplatform.vendormock.api;

import com.lendingplatform.vendormock.pricing.CommissionCalculator;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /v1/commission-quotes}. By the time a request gets here it is authenticated and past chaos. */
@RestController
public class CommissionQuoteController {

    private final CommissionCalculator calculator;

    public CommissionQuoteController(CommissionCalculator calculator) {
        this.calculator = calculator;
    }

    @PostMapping(path = "/v1/commission-quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public QuoteResponse createQuote(@Valid @RequestBody QuoteRequest request) {
        CommissionCalculator.Commission commission =
                calculator.calculate(request.loanAmount(), request.loanTermInMonths(), request.riskBand());
        return new QuoteResponse(UUID.randomUUID().toString(), commission.commissionRate(), commission.totalCommission());
    }
}
