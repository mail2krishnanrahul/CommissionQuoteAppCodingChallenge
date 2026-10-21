# Build Prompt — Commission Quote App

> **How to use this prompt**
> 1. Create an empty Git repository and copy in this kit: `README.md`, `docs/`, `contracts/`. Don't add the challenge brief itself; §4 restates its requirements.
> 2. Install JDK 21 (e.g. Temurin) and Git. Docker is optional.
> 3. Open Claude Code at the repository root and send: **"Read `docs/ai/BUILD_PROMPT.md` and follow it."**
> 4. When it pauses after each phase, review the diff, then reply `continue`. Reply `run straight through` to skip the pauses.
>
> This file is committed on purpose: it's part of the AI-usage transparency the brief asks for.

---

## 1. Role

You are a senior Java engineer pairing with Rahul, who owns this codebase and will maintain it. Optimise for code he can read, review and extend: **clarity over cleverness**.

## 2. Mission

Build a take-home submission for a lending platform: a staff-facing web app that captures loan details and shows a commission quote from a vendor's Commission Quote API, plus a mock of that API (the real one isn't built yet).

Aim for quality, not volume:
- correct behaviour at every edge;
- explicit, well-named failure handling;
- tests that read like a specification;
- docs a reader can skim in five minutes;
- no more machinery than a 4-hour exercise needs.

**Timebox:** 4 hours in total, including Rahul's review. If a phase runs long, use the cut list in `docs/PLAN.md` rather than lowering quality.

## 3. Sources of truth — read these first, in this order

1. `contracts/commission-quote-api.yaml` — the vendor API contract (OpenAPI 3.1).
2. `docs/ARCHITECTURE.md` — design, business rules, error model, ADRs, configuration.
3. `docs/PLAN.md` — phases, acceptance criteria, commit messages, cut list.
4. `README.md` — a draft you'll finalise in Phase 5.

**Precedence when they disagree:** contract → ARCHITECTURE → PLAN → this prompt. If a design detail turns out to be wrong, or doesn't fit the current library versions, don't silently work around it. Propose the change; once Rahul agrees, update the doc in the same commit and add a row to the Decision log (ARCHITECTURE §17).

## 4. The brief, as numbered requirements

Each requirement must be traceable to code and to a test or doc. You'll fill in the traceability table in the README.

| ID | Requirement (from the brief) |
|---|---|
| R1 | Web UI with a form for `loanAmount`, `loanTermInMonths`, `riskBand` |
| R2 | A "Generate Quote" button |
| R3 | A display area showing the quote on success |
| R4 | Proper loading states, and error messages when generation fails |
| R5 | Mock Commission Quote API honouring the contract: request `loanAmount, loanTermInMonths, riskBand`; response `quoteId, commissionRate, totalCommission` |
| R6 | The API requires an `api-key` header; requests without a valid key are rejected |
| R7 | The API occasionally (randomly) throws an error |
| R8 | Core unit/integration tests |
| R9 | Edge cases: invalid numbers from the user; API timeouts and errors |
| R10 | Readable, well-structured code with clear separation of concerns |
| R11 | README with step-by-step run and test instructions |
| R12 | README section on how AI was used |
| N1 | Nice to have: clean, accessible, responsive UI |

## 5. Stack and versions

- **Java 21**; **Maven** multi-module build with the Maven Wrapper (`./mvnw`, `mvnw.cmd`).
- **Spring Boot 4.x, latest stable patch.** Check Maven Central before pinning. Don't use Boot 3.5: its open-source support ended in June 2026.
  Boot 4 runs on Spring Framework 7 and Jackson 3, so don't paste Boot 3-era snippets without checking them. In particular: use `@MockitoBean` (not `@MockBean`), `RestClient`, `ProblemDetail`, and Spring Framework 7's built-in `RetryTemplate` / `RetryPolicy` (package `org.springframework.core.retry`) rather than the separate spring-retry project. Check starter artifact names against the Boot 4 reference docs.
- **Thymeleaf** server-side templates and **htmx 2.0.x** (latest 2.0 patch), served from the `org.webjars.npm:htmx.org` WebJar so Maven manages it and no CDN is involved. htmx 4 is pre-release — don't use it.
- **Plain CSS:** one stylesheet using custom properties. No CSS framework, no Node toolchain, no front-end build step.
- **Tests:** JUnit 5, AssertJ, Spring MockMvc, and WireMock (`org.wiremock:wiremock-standalone`) for HTTP-level tests of the vendor client, including real timeouts. If WireMock clashes with Boot 4's dependencies, switch to OkHttp `MockWebServer` and log the decision.

**Allowed dependencies:** Spring Boot starters for web MVC, Thymeleaf, validation and actuator, plus their test starters; the htmx WebJar (and `webjars-locator-lite` if needed); WireMock or MockWebServer at test scope.
Anything else needs a one-line justification in the Decision log.
**Explicitly not:** Lombok, MapStruct, Spring Security, Resilience4j, databases, Kafka, React, or any JavaScript bundler.

## 6. Repository layout to produce

```text
commission-quote-app/
├── pom.xml                          # parent: Java 21, Boot parent/BOM, plugin management
├── mvnw, mvnw.cmd, .mvn/
├── contracts/commission-quote-api.yaml
├── vendor-mock/                     # Commission Quote API mock (port 8081)
│   └── src/main/java/com/lendingplatform/vendormock/
│       ├── api/          CommissionQuoteController, QuoteRequest, QuoteResponse, ProblemDetailsAdvice
│       ├── pricing/      CommissionCalculator, RiskBand
│       ├── security/     ApiKeyFilter, ApiKeyProperties
│       ├── chaos/        ChaosFilter, ChaosProperties, Sleeper
│       ├── idempotency/  IdempotencyStore
│       └── web/          CorrelationIdFilter
├── quote-app/                       # Staff web app (port 8080)
│   └── src/main/java/com/lendingplatform/quoteapp/
│       ├── quote/        LoanDetails, RiskBand, CommissionQuote, QuoteService,
│       │                 CommissionQuoteGateway (port), QuoteFailure (sealed) + subtypes
│       ├── vendor/       HttpCommissionQuoteGateway, VendorClientProperties, VendorClientConfig,
│       │                 VendorQuoteRequest, VendorQuoteResponse
│       └── web/          QuoteController, QuoteForm, LoanDetailsParser, QuoteFailureHandler,
│                         CorrelationIdFilter, SecurityHeadersFilter
│   └── src/main/resources/  templates/index.html, static/css/app.css, static/js/app.js,
│                            messages.properties, application.yml, application-local.yml
├── docs/ARCHITECTURE.md, docs/PLAN.md, docs/ai/BUILD_PROMPT.md
├── .github/workflows/ci.yml
├── .env.example
├── AGENTS.md, CLAUDE.md
└── README.md
```

The two modules **share no code**. The mock stands in for an external system; the OpenAPI file is the only thing they share (ADR-002). Duplicating the small `RiskBand` enum is deliberate.

## 7. Behaviour you must implement

ARCHITECTURE.md has the detail. These are the points that must not drift.

### 7.1 Vendor mock (`vendor-mock`, port 8081)

- `POST /v1/commission-quotes`, exactly as the contract says; `200` with `{quoteId, commissionRate, totalCommission}`.
- **Filter order:** `CorrelationIdFilter` → `ApiKeyFilter` → `ChaosFilter` → controller. Put the reason in a comment: auth is deterministic, and chaos applies only to authenticated calls, so a missing key always gets a 401 and never a random 503.
- **API key:** read the `api-key` header. Missing, blank or wrong → `401` Problem Details (`code: UNAUTHORIZED`), with the same body for all three. Accepted keys come from config as a set, to allow rotation. Compare in constant time: `MessageDigest.isEqual` on SHA-256 digests, so length doesn't leak either. Never log the key. `/actuator/health` needs no key.
- **Validation is strict.** Reject unknown properties, numbers sent as strings, floats for `loanTermInMonths` (Jackson silently truncates `12.5` to `12` by default — turn that off), lowercase or unknown `riskBand`, missing fields and malformed JSON. Each is a `400` Problem Details (`VALIDATION_FAILED` with `errors[{field, message}]`, or `MALFORMED_REQUEST`), never a 500. Wrong content type → `415`.
- **Pricing** (`CommissionCalculator`, a pure class with no Spring): base rate in basis points by band — LOW 65, MEDIUM 55, HIGH 40; +5 bps when `loanTermInMonths >= 240`; `commissionRate = bps / 10000` at scale 4; `totalCommission = loanAmount × commissionRate`, rounded `HALF_UP` to 2 dp and capped at 25,000.00. `BigDecimal` only — no `double` anywhere money is involved.
- **Chaos** (`ChaosFilter`): with configured probabilities, either return a random `500` or `503` Problem Details (`failure-rate`, default 0.2), or wait longer than the client's timeout and then continue normally (`slow-rate`, default 0.05; `slow-delay`, default 5s). Otherwise add small latency jitter. Randomness comes from an injected `RandomGenerator` and waiting from an injected `Sleeper`, so tests are deterministic. The `X-Mock-Scenario: ok|error|slow` header forces an outcome when `mock.chaos.scenario-header-enabled=true` (default `true` for a mock; document it).
- **Idempotency** (`IdempotencyStore`): optional `Idempotency-Key` header. Same key and same payload → replay the stored response (same `quoteId`) with `Idempotent-Replayed: true`. Same key and a different payload → `422` (`IDEMPOTENCY_KEY_REUSED`). Fingerprint normalised values, so `500000` and `500000.00` count as the same payload. In memory, TTL 10 minutes, bounded size. Concurrent duplicates must resolve to one stored response (`putIfAbsent`).
- **Errors:** RFC 9457 Problem Details (`application/problem+json`) everywhere, with extra `code` and `correlationId` members.
- Enable virtual threads (`spring.threads.virtual.enabled=true`) so simulated latency doesn't tie up platform threads.

### 7.2 Quote app (`quote-app`, port 8080)

- `GET /` renders the form; `POST /quotes` handles a submission. When the `HX-Request` header is present, return the `quotePanel` fragment; otherwise return the full page, so the form also works without JavaScript.
- **Input parsing** (`LoanDetailsParser`): the form posts raw strings, and the parser turns them into a `LoanDetails` record or field errors, using the rules and exact messages in ARCHITECTURE §6.1.
  - **Accept:** `250000`, `250,000`, `$250,000.50`, and surrounding spaces.
  - **Reject:** blank; `abc`; `12abc`; `1e6` (`new BigDecimal` accepts it — don't let it through); `NaN`; `Infinity`; `1,00,000` and `12,34` (commas only as thousands separators); negatives; `0`; values below 1,000 or above 5,000,000; more than 2 decimal places; more than 20 characters; a term that isn't a whole number from 12 to 360 (including `12.5` and `12.0`); a missing or tampered `riskBand`.
- **Gateway** (`HttpCommissionQuoteGateway` implements the `CommissionQuoteGateway` port):
  - `RestClient` with connect timeout 1s and read timeout 2s (configurable).
  - Sends `api-key`, `Idempotency-Key`, `X-Correlation-Id`, `Content-Type` and `Accept`.
  - Bounded retry with `RetryTemplate`: `max-retries` default 1 (two attempts), exponential backoff from 200ms with jitter. Retry **only** on 500/502/503/504, connection failures and read timeouts, reusing the same idempotency key on every attempt. Never retry a 4xx.
  - Log each attempt (attempt number, status or exception type, duration, correlation id) — never the key.
- **Tolerant reader with sanity checks:** ignore unknown response fields, but raise `VendorBadResponse` for unparseable JSON, a missing or blank `quoteId`, a missing number, a `commissionRate` outside [0, 1], or a negative `totalCommission`.
- **Failure taxonomy:** `QuoteFailure` is a `sealed` exception with exactly `VendorUnavailable`, `VendorTimeout`, `VendorRejected`, `VendorMisconfigured` and `VendorBadResponse`. One handler, `QuoteFailureHandler`, maps each with a pattern-matching `switch` to an HTTP status, a user message, and whether to show "Try again", per the table in ARCHITECTURE §7.2. Upstream failures return 502/503/504, not 200 (ADR-012).
- **Idempotency key lifecycle** (ARCHITECTURE §10.3): a fresh UUID in a hidden field whenever a new form is rendered; kept across validation errors and vendor failures, so "Try again" can't create a duplicate quote; replaced after a success.
- **API key handling:** bind `VENDOR_API_KEY` into `@Validated @ConfigurationProperties` with `@NotBlank`, so the app fails fast at startup if it's missing. Redact it in `toString()`. It must never reach a template, the browser, a log line or an error message.
- **Zero-config local run:** a `local` Spring profile in each module holds the same obviously fake key, `local-dev-key-not-a-secret`. No other profile has a default key.
- **Env vars:** map every documented env var explicitly with `${ENV_VAR:default}` placeholders in `application.yml` (ARCHITECTURE §12). Don't rely on relaxed binding — `mock.chaos.failure-rate` would otherwise need `MOCK_CHAOS_FAILURERATE`.
- Validate configuration at startup (rates within 0–1 and summing to ≤ 1, latency min ≤ max, positive durations) and fail with a clear message.

### 7.3 UI (Thymeleaf + htmx)

- One page, one card: the form on top, the outcome area below.
  - Amount and term inputs use `type="text"` with `inputmode="decimal"` / `inputmode="numeric"` (ADR-010).
  - Risk band is a radio group in a `fieldset` with a `legend`.
  - Hint text sits under each label.
  - The form has `novalidate`; the server is the single source of validation messages.
- **htmx:** `hx-post="/quotes"`, `hx-target="#quote-panel"`, `hx-swap="outerHTML"`, `hx-indicator` for the spinner, and `hx-disabled-elt` to prevent double submits. Keep `action` and `method` on the form for the no-JS path.
- **Error responses:** htmx 2 doesn't swap 4xx/5xx responses by default. Configure `responseHandling` in an `htmx-config` meta tag so 2xx, 422 and 500/502/503/504 fragments are swapped and other statuses aren't. Set `timeout` to 10000ms, above the server's worst-case budget.
- **Client-only failures:** in `static/js/app.js` — the only JavaScript you write; keep it under about 40 lines, with comments — listen for `htmx:sendError`, `htmx:timeout`, and any `htmx:responseError` that wasn't swapped, and show a static, accessible message in the outcome area.
- **CSP-friendly:** no inline scripts, no `hx-on` attributes, `allowEval: false`, `includeIndicatorStyles: false` (otherwise htmx injects an inline `<style>`). Provide the `.htmx-indicator` CSS yourself.
- **States to render** (ARCHITECTURE §7.3): idle, loading, success, validation error, upstream error (retryable and not), and client-side network error/timeout.
  - Success shows the total commission in AUD (`$3,000.00`, `en-AU`), the rate as a percentage (`0.60%`), the quote ID, an echo of the inputs, and "Generated at" in `Australia/Sydney` time from an injected `Clock`.
  - Every server-rendered error shows "Reference: {correlationId}".
- **Accessibility:**
  - Labels tied to inputs; `aria-describedby` linking hint and error; `aria-invalid` on bad fields.
  - The error area has `role="alert"`.
  - After each swap, focus moves to the first invalid field, the error alert, or the result heading (`tabindex="-1"` plus `autofocus`).
  - Visible focus styles and AA contrast; the spinner respects `prefers-reduced-motion`; the button reads "Generating…" with `aria-busy="true"` while loading.
- **Responsive:** single column, `max-width: 40rem`, usable at 320px wide with no horizontal scroll.

### 7.4 Cross-cutting

- `CorrelationIdFilter` in both modules: reuse an incoming `X-Correlation-Id` if it matches `^[A-Za-z0-9-]{1,64}$`, otherwise generate a UUID. Put it in the MDC and the log pattern, and echo it on the response. The app forwards it to the vendor.
- `SecurityHeadersFilter` in the app: `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'self'`, plus `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`.
- Actuator: expose `health` only, in both modules.
- Logging: one INFO line per quote outcome (band, term, attempts, duration, quoteId — **not** the loan amount); WARN per retry; DEBUG for attempt detail; ERROR only for unexpected failures. Never the API key; never whole request bodies.

## 8. Tests you must write

Tests are the specification. Use `@ParameterizedTest` tables for input rules, and give every test a descriptive name (`@DisplayName`, or `method_condition_expected`).

Keep them deterministic and fast:
- no real randomness — inject `RandomGenerator`, `Sleeper` and `Clock`;
- no sleep longer than 100ms; timeout tests use a ~250ms read timeout against a ~1s WireMock delay;
- no network calls except to localhost;
- `./mvnw verify` finishes in well under a minute.

**vendor-mock**

1. `CommissionCalculatorTest` — every row of the ARCHITECTURE §6.2 table, including: 500,000 / 360 / MEDIUM → 0.0060 and 3,000.00; 1,090.00 / 12 / LOW → 7.09 (documents HALF_UP over HALF_EVEN); 5,000,000 / 300 / LOW → 25,000.00 (cap); 239 vs 240 months.
2. `ApiKeyFilterTest` — missing, blank, wrong, and wrong-length keys → 401 with identical bodies; valid → passes through; a second rotated key is accepted; the health endpoint is open.
3. `QuoteRequestValidationTest` — one table of bad payloads → 400 with the right `errors[].field` or `MALFORMED_REQUEST` (include `12.5` for the term, `"12"` as a string, `"low"`, `"EXTREME"`, an unknown property, and each missing field); malformed JSON → 400; wrong content type → 415.
4. `ChaosFilterTest` — forced error → 500/503 Problem Details; slow → `Sleeper` called with `slow-delay`; disabled → passes through; scenario header honoured, and ignored when the setting is off.
5. `IdempotencyTest` — a replay returns the same `quoteId` and `Idempotent-Replayed: true`; `500000` and `500000.00` count as the same payload; same key with a different payload → 422; concurrent duplicates produce one stored response.

**quote-app**

6. `LoanDetailsParserTest` — the accepted and rejected inputs from §7.2, each asserting the exact user message.
7. `HttpCommissionQuoteGatewayTest` (WireMock):
   - sends every required header;
   - maps a success;
   - 503 then 200 → succeeds on the second attempt, **with the same `Idempotency-Key` on both requests** (verify it);
   - 503 twice → `VendorUnavailable`;
   - delay beyond the read timeout → `VendorTimeout`;
   - 401 → `VendorMisconfigured` after exactly one request;
   - 400 → `VendorRejected` with no retry;
   - malformed JSON, or a missing `quoteId` → `VendorBadResponse`;
   - connection refused → `VendorUnavailable`.
8. `QuoteControllerTest` (`@WebMvcTest`, with the service or gateway as `@MockitoBean`):
   - GET renders the form with an idempotency key;
   - a valid htmx POST → 200 fragment with the formatted rate, AUD amount and quote ID, and a new idempotency key;
   - invalid input → 422 fragment with inline errors, the user's values kept, and the same key;
   - each `QuoteFailure` → the expected status, message, "Try again" shown or hidden, a correlation reference, and the same key;
   - a non-htmx POST → full page;
   - the API key never appears in any response body.
9. `QuoteAppApplicationTest` — the context fails to start without `VENDOR_API_KEY` (outside the `local` profile) and starts with it.

## 9. Working agreement

1. **Before coding:** check `java -version` (21) and look up the current Boot 4 and htmx 2.0 versions on Maven Central. Restate the plan in 10 lines or fewer and list any assumptions or open questions. Carry on unless something blocks you.
2. **One phase at a time,** in `docs/PLAN.md` order. A phase is done when its acceptance criteria pass and `./mvnw verify` is green.
3. **Commit** at the end of each phase with the Conventional Commit message in PLAN.md. Keep diffs small and reviewable; no "WIP" commits.
4. **Pause after each phase** and print:
   - what was built (5 bullets or fewer);
   - the decisions you made, and why;
   - the 3–6 files Rahul should read first;
   - anything you'd flag in review.
   Then wait for `continue`. If Rahul has said `run straight through`, keep printing the summaries but don't wait.
5. **Never** skip, `@Disabled`, or weaken a test to make it pass. If a test fails for a reason you don't understand, stop and explain.
6. **Code style:**
   - records for DTOs and value types;
   - constructor injection with `final` fields;
   - package by feature; small methods;
   - domain names from the brief (`loanAmount`, `riskBand`, `quoteId`);
   - named constants or configuration instead of magic numbers;
   - comments explain *why*, not *what*;
   - no dead code, no commented-out code, and no TODOs except the marked README placeholders for Rahul.
7. **Keep docs in step with the code:** ports, env var names, commands and class names. Any deviation from ARCHITECTURE gets a Decision log row in the same commit.

## 10. Documentation deliverables (Phase 5)

- **README.md** — finish the draft:
  - Run every command in it yourself, and fix any that don't work exactly as written.
  - Complete the traceability table (R1–R12, N1 → where implemented → test or evidence) with real class and test names.
  - Correct the failure-rate wording if the behaviour changed.
  - Delete each `<!-- Phase 5: … -->` comment once its task is done.
  - In **How I used AI**, keep the `<!-- Rahul: … -->` comments for Rahul to complete. Describe accurately what the AI did; don't invent claims about what Rahul changed by hand.
- **docs/ARCHITECTURE.md** — update to as-built: correct the §14 code tour paths and class names, add Decision log rows for anything that changed, and keep the diagrams accurate.
- **docs/PLAN.md** — tick completed phases and acceptance criteria; record anything cut under "Cut list — outcome".
- **AGENTS.md** (60 lines or fewer), for AI assistants working in this repo: what the project is; the module map; build, test and run commands; the conventions in §9.6; the end-to-end path for adding a new request field; and "don't" rules (no new dependencies without a Decision log row, no shared code between modules, money is `BigDecimal`).
- **CLAUDE.md** — one line: `@AGENTS.md`.
- **.env.example** — every env var, with a safe placeholder and a comment.
- **.github/workflows/ci.yml** — Temurin 21, Maven cache, `./mvnw -B verify`.

## 11. Definition of done — run this checklist and report the results

- [ ] `./mvnw clean verify` passes from a clean clone.
- [ ] Both services start with the README commands, and `http://localhost:8080` works.
- [ ] Manual smoke test, each step reported pass or fail:
  - a valid quote (500000 / 360 / Medium → $3,000.00 at 0.60%);
  - invalid amount `12abc`; invalid term `12.5`;
  - forced vendor error (`MOCK_CHAOS_FAILURE_RATE=1`) shows a retryable error with a reference;
  - forced slow vendor (`MOCK_CHAOS_SLOW_RATE=1`) shows the timeout message;
  - vendor stopped shows the unavailable message;
  - `curl` without `api-key` → 401; with it → 200; same `Idempotency-Key` again → same `quoteId`;
  - the form still works with JavaScript disabled;
  - no CSP errors in the browser console.
- [ ] A search of the repo, the built templates and the logs for the API key value finds only `.env.example` placeholders and the `local` profile files.
- [ ] `git ls-files` shows no `.env` file and no copy of the challenge brief.
- [ ] Every requirement in the traceability table has evidence.
- [ ] The docs match the code.

## 12. Final report

Finish with:
- a one-paragraph summary;
- how to run it (three commands);
- a suggested 10-minute review path through the code;
- deviations from the docs, and why;
- known limitations.

## 13. Scope: what to add, and what not to

**Do:** handle every edge correctly; make failure behaviour explicit and honest; write tests a reviewer can read as a spec; make the vendor swappable through configuration alone; make the UI accessible; keep the docs short and accurate.

**Don't:** add databases, auth servers, message queues, React, extra services, or coverage-chasing tests, or introduce single-implementation abstractions "for the future". The gateway port is the one exception, and ARCHITECTURE §3.1 explains why. Containers come only as optional stretch item S1, after Phase 5.
