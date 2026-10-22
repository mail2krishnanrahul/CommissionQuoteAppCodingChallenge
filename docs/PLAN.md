# Delivery Plan — Commission Quote App

**Timebox:** 4 hours, including review (the brief's guideline).
**How it's built:** Claude Code follows [`docs/ai/BUILD_PROMPT.md`](ai/BUILD_PROMPT.md) phase by phase. Rahul reviews at each phase gate before the next phase starts.
**Design reference:** [`docs/ARCHITECTURE.md`](ARCHITECTURE.md). Section numbers below (e.g. §6.1) point there.

---

## Definition of done

- [x] Every brief requirement (R1–R12) and the nice-to-have (N1) has evidence in the README traceability table.
- [ ] `./mvnw clean verify` is green locally and in CI.
- [ ] The manual smoke test at the end of this file passes, step by step.
- [x] Docs match the code: ports, env var names, commands, class names.
- [x] Rahul has read through the code.

---

## Phase overview

| # | Phase | Budget | Running total | Commit message |
|---|---|---:|---:|---|
| 0 | Scaffold and contract | 20m | 0:20 | `chore: scaffold maven multi-module build, CI and API contract` |
| 1 | Vendor mock — pricing, contract, auth | 40m | 1:00 | `feat(vendor-mock): commission quote endpoint with api-key auth` |
| 2 | Vendor mock — chaos and idempotency | 25m | 1:25 | `feat(vendor-mock): simulated failures, latency and idempotent replay` |
| 3 | Quote app — vendor gateway and resilience | 45m | 2:10 | `feat(quote-app): vendor gateway with timeouts, bounded retry and failure taxonomy` |
| 4 | Quote app — web layer and UI | 50m | 3:00 | `feat(quote-app): quote form with htmx states and accessible result display` |
| 5 | Hardening and documentation | 35m | 3:35 | `docs: finalise README, as-built architecture and agent guide` |
| 6 | Final review and smoke test | 25m | 4:00 | fixes only, e.g. `fix(quote-app): …` |
| S | Stretch — only if under budget | — | — | `feat: …` per item |

AI generation is fast; **review is the real cost**. Each budget includes reading the diff at the gate.

---

## Phase 0 — Scaffold and contract (20m)

**Tasks**
- [x] Parent `pom.xml`: Java 21, Spring Boot 4.x parent (latest stable patch, checked on Maven Central), modules `vendor-mock` and `quote-app`, Maven Wrapper.
- [x] Two minimal Boot apps on 8081 and 8080, each with `/actuator/health` only.
- [x] `local` profile in each module with the fake key `local-dev-key-not-a-secret`; other profiles require a real key.
- [x] `.gitignore`, `.editorconfig`, `.env.example`, `contracts/commission-quote-api.yaml` in place.
- [x] `.github/workflows/ci.yml`: Temurin 21, Maven cache, `./mvnw -B verify`.

**Acceptance criteria**
- `./mvnw verify` is green.
- Both apps start with the README commands and report `{"status":"UP"}`.

**Review focus**
- Why two deployables and no shared module (ADR-002).
- What the `local` profile is for, and why nothing else has a default key (§9.1).

---

## Phase 1 — Vendor mock: pricing, contract, auth (40m)

**Tasks**
- [x] `pricing/RiskBand`, `pricing/CommissionCalculator`, written test-first from the §6.2 table.
- [x] `api/QuoteRequest` and `api/QuoteResponse` records; strict Jackson settings: fail on unknown properties, no float-to-int, no string-to-number coercion, `BigDecimal` for floats.
- [x] `api/CommissionQuoteController` → `POST /v1/commission-quotes`.
- [x] `api/ProblemDetailsAdvice` — every error as Problem Details with `code` and `correlationId` (§7.1).
- [x] `web/CorrelationIdFilter`, `security/ApiKeyFilter` (+ `ApiKeyProperties`), registered in the §3.2 order.

**Tests:** `CommissionCalculatorTest`, `ApiKeyFilterTest`, `QuoteRequestValidationTest`.

**Acceptance criteria**
- Every row of the §6.2 table passes.
- Every invalid payload returns 400 (or 415), never 500.
- `curl` without `api-key` → 401; with the local key → 200 with the expected body.

**Review focus**
- Filter order, and why auth comes before chaos.
- Constant-time comparison on digests, and why "missing" and "wrong" look identical.
- Why HALF_UP, and what the 1,090.00 case shows.
- The Jackson coercion trap (`12.5` silently becoming `12`).

---

## Phase 2 — Vendor mock: chaos and idempotency (25m)

**Tasks**
- [x] `chaos/ChaosProperties` (validated), `chaos/ChaosFilter`, `chaos/Sleeper`; `RandomGenerator` and `Sleeper` as beans.
- [x] `X-Mock-Scenario: ok|error|slow` when `mock.chaos.scenario-header-enabled=true`.
- [x] `idempotency/IdempotencyStore`: request fingerprint uses normalised values (`500000` and `500000.00` are the same payload); TTL 10m; bounded size; `putIfAbsent` for concurrent duplicates.
- [x] `spring.threads.virtual.enabled=true`.

**Tests:** `ChaosFilterTest`, `IdempotencyTest`.

**Acceptance criteria**
- `MOCK_CHAOS_FAILURE_RATE=1 MOCK_CHAOS_SLOW_RATE=0` → every authenticated call returns 500 or 503.
- `X-Mock-Scenario: slow` → the response takes about `slow-delay`.
- The same `Idempotency-Key` returns the same `quoteId` with `Idempotent-Replayed: true`; a different body with that key → 422.

**Review focus**
- Where randomness enters, and how the tests control it.
- Why idempotency matters even though chaos fails *before* processing: a slow request completes on the vendor after the client has given up and retried.

---

## Phase 3 — Quote app: vendor gateway and resilience (45m)

**Tasks**
- [x] `quote/` package: `LoanDetails`, `RiskBand`, `CommissionQuote`, `CommissionQuoteGateway` (port), `QuoteService`, sealed `QuoteFailure` with its five subtypes.
- [x] `vendor/VendorClientProperties`: `@Validated`, `@NotBlank` key, redacted `toString`.
- [x] `vendor/VendorClientConfig`: `RestClient` on the JDK HTTP client with connect and read timeouts.
- [x] `vendor/HttpCommissionQuoteGateway`: headers, `RetryTemplate` policy (§8), error classification (§7.2), response sanity checks (ADR-011), attempt logging without the key.

**Tests:** `HttpCommissionQuoteGatewayTest` (WireMock), `QuoteAppApplicationTest`.

**Acceptance criteria**
- Every gateway scenario in BUILD_PROMPT §8 item 7 passes.
- The retry test verifies the **same** `Idempotency-Key` on both requests.
- 401 and 400 are attempted exactly once.
- The key value doesn't appear in the test log output.

**Review focus**
- The retryable vs non-retryable table, and why 4xx is never retried.
- The timeout budget arithmetic (≈ 4.3s server, 10s client).
- Why vendor 401 becomes 502 for the browser.
- Why the failure hierarchy is sealed.

---

## Phase 4 — Quote app: web layer and UI (50m)

**Tasks**
- [x] `web/QuoteForm` (raw strings + idempotency key) and `web/LoanDetailsParser` (§6.1, exact messages).
- [x] `web/QuoteController`: `GET /`, `POST /quotes`; fragment when `HX-Request` is present, full page otherwise; idempotency key lifecycle (§10.3).
- [x] `web/QuoteFailureHandler`: exhaustive `switch` → status, message, retry flag, reference.
- [x] `templates/index.html` with the `quotePanel` fragment; `static/css/app.css`; `static/js/app.js`; htmx WebJar and `htmx-config` meta tag (§10.2).
- [x] `web/CorrelationIdFilter`, `web/SecurityHeadersFilter`; injected `Clock` for "Generated at".

**Tests:** `LoanDetailsParserTest`, `QuoteControllerTest`.

**Acceptance criteria**
- Every UI state in §7.3 renders, checked manually and by test.
- A keyboard-only run-through works, and focus lands as §7.3 says.
- Usable at 320px width.
- With JavaScript disabled, the form still produces a quote.
- No CSP violations in the browser console.

**Review focus**
- The htmx request lifecycle, and why `responseHandling` is configured.
- How focus is managed after a swap.
- The idempotency key lifecycle in the form.
- Why `type="text"` with `inputmode` (ADR-010).

---

## Phase 5 — Hardening and documentation (35m)

**Tasks**
- [x] README: run **every** command as written; fill in the traceability table; leave the `<!-- Rahul: … -->` comments in "How I used AI" for Rahul.
- [x] ARCHITECTURE: correct the §14 code tour to the real paths; add Decision log rows for any deviation; check the diagrams still match.
- [x] `AGENTS.md` (≤60 lines) and `CLAUDE.md` (`@AGENTS.md`) for AI assistants working in the repo.
- [x] `.env.example` complete and commented.
- [x] Run the definition-of-done checklist in BUILD_PROMPT §11 and report results.

**Acceptance criteria:** a reviewer can clone, run, test and understand the project from the README alone.

---

## Phase 6 — Final review and smoke test (25m)

- [x] Rahul reads through the code.
- [x] Rahul runs both services and tries quotes and the failure paths.
- [x] Rahul checks the commission maths by hand.
- [x] Complete "How I used AI" in the README.
- [ ] Run the full manual smoke test end to end.
- [ ] Push, and check that CI is green.

---

## Stretch (only if under budget, in this order)

| # | Item | Value | Size |
|---|---|---|---|
| S1 | `docker-compose.yml` plus multi-stage Dockerfiles (non-root, healthchecks) → `docker compose up --build` | One-command start for reviewers | ~20m |
| S2 | Contract conformance tests: validate vendor-mock responses and the app's WireMock stubs against the OpenAPI file | Catches drift on both sides of the contract | ~25m |
| S3 | Playwright for Java smoke test (happy path and forced error) under an `e2e` Maven profile | Real browser evidence of the htmx states | ~30m |
| S4 | Micrometer metrics for vendor calls (latency, outcome, attempts) | Visibility into vendor health | ~15m |

## Cut list — if a phase overruns, cut in this order

1. All stretch items.
2. `SecurityHeadersFilter` / CSP (note it in README "Next steps").
3. The no-JavaScript full-page fallback (keep the htmx path).
4. The mock's idempotency replay store (keep generating and forwarding the key in the app; note it as the next step).
5. Latency jitter in chaos (keep failure and slow modes).

**Never cut:** input parsing tests, gateway tests, the failure taxonomy, or the README run instructions.

### Cut list — outcome
Nothing was cut: every item in Phases 0–5 was built, including the CSP filter, the no-JavaScript fallback, the idempotency replay store and latency jitter. Stretch items S1–S4 were not started. Deviations from the design are in ARCHITECTURE §17.

---

## Manual smoke test (≈ 3 minutes)

1. Start both services with the `local` profile (README Quick start).
2. **Happy path:** `500000` · `360` · Medium → **$3,000.00** at **0.60%**, with quote ID and "Generated at".
3. **Validation:** amount `12abc`, term `12.5`, no band → three inline errors; focus on the amount field; values kept.
4. **Friendly input + rounding:** `$1,090` · `12` · Low → **$7.09** (HALF_UP: 7.085 rounds up).
5. **Cap:** `5,000,000` · `300` · Low → **$25,000.00**.
6. **Vendor errors:** restart the mock with `MOCK_CHAOS_FAILURE_RATE=1 MOCK_CHAOS_SLOW_RATE=0` → retryable error with a reference. Check both services' logs: same correlation id, two attempts.
7. **Vendor slow:** `MOCK_CHAOS_SLOW_RATE=1 MOCK_CHAOS_FAILURE_RATE=0` → timeout message after about 4 seconds.
8. **Vendor down:** stop the mock → "temporarily unavailable".
9. **API directly:** `curl` without `api-key` → 401; with it → 200; repeat with the same `Idempotency-Key` → same `quoteId`.
10. **Accessibility:** keyboard-only pass at a 320px-wide viewport.

---

## Risks and mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Boot 4 / Jackson 3 / Framework 7 APIs differ from older examples | High | The prompt calls this out; check the reference docs; log deviations in the Decision log |
| WireMock dependency conflicts with Boot 4 | Medium | Use the standalone artefact; fall back to OkHttp `MockWebServer` |
| htmx doesn't swap error responses | High if missed | `responseHandling` config (§10.2); controller tests assert status codes; manual check in Phase 4 |
| Flaky timing tests | Medium | Injected `Sleeper`; short WireMock delays; no real randomness |
| CSP blocks htmx behaviour | Medium | `allowEval=false`, `includeIndicatorStyles=false`, no inline handlers; check the console in Phase 4 |
| Overrunning the timebox | Medium | Phase gates plus the cut list; stretch work only after Phase 5 |
