# AGENTS.md — working in this repo

A staff web app (`quote-app`, :8080) that shows a commission quote from a vendor API, plus a mock of that API
(`vendor-mock`, :8081). Java 21, Spring Boot 4.1, Thymeleaf + htmx 2.0, Maven multi-module. Design: `docs/ARCHITECTURE.md`.

## Module map
- `contracts/commission-quote-api.yaml` — OpenAPI 3.1 contract. The **only** thing the two modules share.
- `vendor-mock/…/vendormock/` — `api/` controller, records, Problem Details · `pricing/` `CommissionCalculator` (pure) ·
  `security/` `ApiKeyFilter` · `chaos/` `ChaosFilter` · `idempotency/` `IdempotencyStore` · `web/` correlation id.
  Filter order: CorrelationId → ApiKey → Chaos → controller.
- `quote-app/…/quoteapp/` — `quote/` domain + `QuoteService` + `CommissionQuoteGateway` port + sealed `QuoteFailure` ·
  `vendor/` `HttpCommissionQuoteGateway` (RestClient, RetryTemplate) · `web/` `QuoteController`, `LoanDetailsParser`,
  `QuoteFailureHandler`, filters · `resources/templates/index.html` (`quotePanel` fragment), `static/js/app.js`.

## Commands (Windows: `mvnw.cmd`, `scripts\start.ps1`, `scripts\stop.ps1`)
```bash
./mvnw verify                                                          # all tests, < 1 minute
./mvnw -pl quote-app test -Dtest=LoanDetailsParserTest                 # one class
scripts/start.sh [--build]   # both services in the background (local profile); logs in .run/; scripts/stop.sh stops them
./mvnw -pl vendor-mock spring-boot:run -Dspring-boot.run.profiles=local   # or one service in the foreground
./mvnw -pl quote-app spring-boot:run -Dspring-boot.run.profiles=local     # http://localhost:8080
```

## Conventions
- Records for DTOs and value types; constructor injection with `final` fields; package by feature; small methods.
- Domain names from the brief: `loanAmount`, `loanTermInMonths`, `riskBand`, `quoteId`, `commissionRate`, `totalCommission`.
- Named constants or configuration, not magic numbers. Comments say *why*. No dead or commented-out code.
- Money is `BigDecimal` with an explicit `RoundingMode` — never `double`. Rates are basis points in the mock.
- Tests are the spec: `@ParameterizedTest` tables, `@DisplayName` on every test. Inject `RandomGenerator`, `Sleeper`,
  `Clock`; no sleeps over 100ms; only localhost network. Never skip, disable or weaken a test.
- Every user-visible message is fixed text: parser messages in `LoanDetailsParser`, failure messages in
  `QuoteFailureHandler`, page copy in `messages.properties`. Never render an exception message.
- Thymeleaf `th:text` only (never `th:utext`). No inline scripts or styles, no `hx-on` (CSP).

## Adding a request field end to end (e.g. `loanPurpose`)
1. Contract: add it to `QuoteRequest` in `contracts/commission-quote-api.yaml`.
2. Mock: `api/QuoteRequest` (+ Bean Validation), `QuoteRequest.fingerprint()`, `CommissionCalculator` if it
   affects price; extend `QuoteRequestValidationTest` and `IdempotencyTest`.
3. App: `quote/LoanDetails` → `web/QuoteForm` → `web/LoanDetailsParser` (rule + exact message) →
   `vendor/VendorQuoteRequest.from` → `templates/index.html` (label, hint, error, `aria-describedby`).
4. Tests: `LoanDetailsParserTest` rows, `QuoteControllerTest` (rendered + kept on error),
   `HttpCommissionQuoteGatewayTest` (`equalToJson` body).
5. Docs: ARCHITECTURE §5/§6.1 and the README traceability table.

## Don't
- Add a dependency without a row in the Decision log (ARCHITECTURE §17).
- Share code between `vendor-mock` and `quote-app` (ADR-002) — duplicate the small enum instead.
- Let the API key reach a template, log line, error message or `toString()`.
- Retry a 4xx, or send a retry without the same `Idempotency-Key`.
- Return 200 for an upstream failure (ADR-012), or add a `QuoteFailure` subtype without handling it in the `switch`.
