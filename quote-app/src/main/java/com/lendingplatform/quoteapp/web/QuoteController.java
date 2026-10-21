package com.lendingplatform.quoteapp.web;

import com.lendingplatform.quoteapp.quote.CommissionQuote;
import com.lendingplatform.quoteapp.quote.QuoteFailure;
import com.lendingplatform.quoteapp.quote.QuoteService;
import com.lendingplatform.quoteapp.quote.RiskBand;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * The quote page. {@code POST /quotes} answers htmx with just the {@code quotePanel} fragment, and a plain browser
 * post (no JavaScript) with the full page, so the form works either way.
 *
 * <p>Idempotency key lifecycle (ARCHITECTURE §10.3): a new key for every fresh form; the <em>same</em> key after a
 * validation error or a vendor failure, so "Try again" can't create a duplicate quote; a new key after a success, so
 * the next submission is a new quote even with identical inputs.
 */
@Controller
public class QuoteController {

    static final String HX_REQUEST_HEADER = "HX-Request";

    private static final Logger log = LoggerFactory.getLogger(QuoteController.class);
    private static final String PAGE = "index";
    private static final String PANEL = "index :: quotePanel";

    private final LoanDetailsParser parser;
    private final QuoteService quoteService;
    private final QuoteFailureHandler failureHandler;
    private final Clock clock;

    public QuoteController(LoanDetailsParser parser, QuoteService quoteService, QuoteFailureHandler failureHandler,
                           Clock clock) {
        this.parser = parser;
        this.quoteService = quoteService;
        this.failureHandler = failureHandler;
        this.clock = clock;
    }

    @GetMapping("/")
    public String showForm(Model model) {
        populate(model, QuoteForm.empty(newIdempotencyKey()), Map.of());
        return PAGE;
    }

    @PostMapping("/quotes")
    public String submitQuote(@ModelAttribute QuoteForm submitted,
                              @RequestHeader(name = HX_REQUEST_HEADER, required = false) String htmxRequest,
                              Model model, HttpServletResponse response) {
        String view = htmxRequest != null ? PANEL : PAGE;
        QuoteForm form = submitted.withIdempotencyKey(validOrNewIdempotencyKey(submitted.idempotencyKey()));

        switch (parser.parse(form)) {
            case LoanDetailsParser.Result.Invalid invalid -> {
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                populate(model, form, invalid.fieldErrors());
                model.addAttribute("firstInvalidField", invalid.fieldErrors().firstEntry().getKey());
            }
            case LoanDetailsParser.Result.Valid valid -> {
                try {
                    CommissionQuote quote = quoteService.requestQuote(valid.loanDetails(), form.idempotencyKey());
                    populate(model, form.withIdempotencyKey(newIdempotencyKey()), Map.of());
                    model.addAttribute("result", QuoteResultView.of(quote, valid.loanDetails(), clock));
                } catch (QuoteFailure failure) {
                    showFailure(model, response, form, failureHandler.handle(failure));
                } catch (RuntimeException unexpected) {
                    log.error("Unexpected error requesting a quote", unexpected);
                    showFailure(model, response, form, failureHandler.unexpected());
                }
            }
        }
        return view;
    }

    private static void showFailure(Model model, HttpServletResponse response, QuoteForm form,
                                    QuoteFailureHandler.FailureView failure) {
        response.setStatus(failure.status().value());
        populate(model, form, Map.of());
        model.addAttribute("failure", failure);
        model.addAttribute("reference", CorrelationIdFilter.current());
    }

    private static void populate(Model model, QuoteForm form, Map<String, String> fieldErrors) {
        model.addAttribute("form", form);
        model.addAttribute("errors", fieldErrors);
        model.addAttribute("riskBands", RiskBand.values());
    }

    /** The hidden field comes from the browser, so only a well-formed UUID is passed on to the vendor. */
    private static String validOrNewIdempotencyKey(String submitted) {
        try {
            return UUID.fromString(submitted).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            return newIdempotencyKey();
        }
    }

    private static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }
}
