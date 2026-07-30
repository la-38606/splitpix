# SplitPix Design Addendum (v2.2): Hosting and Web UI

**Status:** v2.2 — addendum to the v2.1 design document (`splitpix_design_doc_v2.pdf`), revised after a three-lens adversarial review (scope consistency, security/privacy, factual accuracy).
**Author:** Luiz
**Scope rule:** Nothing in this addendum may delay the MVP defined in sections 24–25 of v2.1. Work below begins only after the v1.0 tag.

This addendum turns two unordered bullets from section 26 ("Deployment to Render, Railway, or Fly.io" and "Thymeleaf web UI") into concrete, ordered plans. Section numbering continues from v2.1, which ends at section 33.

**Base-doc decisions amended here:** §14.3 (request hashing: stretch → required for the UI), §20 (compose gains an optional app service), §21 (message bundle file name), §26 (stretch-goal ordering), §28.5 (idempotency key transport gains a form-field variant), §30 ("Flyway if quick" → required before hosting).

---

## 34. Ordering Decision

**Deployment ships before the UI.**

Rationale:

- Deployment risk (configuration, environment, database provisioning) is independent of UI risk (templates, UX). Retiring infrastructure risk first means the UI later ships onto proven configuration.
- A live URL is immediate portfolio value for the API that already exists; the UI then lands as an increment on a working deployment instead of one big-bang launch.

During the API-only phase, `GET /` redirects to the GitHub README so a visitor clicking the live URL sees documentation, not a 404. This addendum also formally declares `GET /api/v1/ping` (added at Day 0 as the "trivial endpoint" of §24) as the permanent health endpoint; §15 predates it.

Effort estimate: one focused day including Flyway adoption and verification of the §35 preconditions — not "an afternoon"; platform-side setup alone is an afternoon.

---

## 35. Hosting Plan

### 35.1 Decision

Host on **Render** (first choice): free web-service tier still exists (spins down after 15 idle minutes; ~1-minute cold start — acceptable for a demo), `PORT` injected, managed certs with forced HTTPS, managed Postgres.

Fallbacks, in order:

1. **Railway** — usage-based, ~US$5/month, named in §26.
2. **Fly.io Machine + external free Postgres (Neon or Supabase)** — Fly has no free tier for new accounts and its true Managed Postgres starts at ~US$38/month; its legacy cheap Postgres is explicitly unmanaged and deprecated. Fly is therefore viable only with an external database.

AWS (App Runner or Elastic Beanstalk, plus RDS) remains a documented alternative, not the choice: it adds IAM, VPC, and pricing complexity without adding signal for this project's target skills. One README sentence records this tradeoff.

### 35.2 Build artifact

- Multi-stage `Dockerfile`: Maven + Temurin 21 build stage; JRE 21 runtime stage using Spring Boot layered-jar extraction (`java -Djarmode=tools -jar app.jar extract --layers --launcher` — the old `layertools` jarmode is deprecated). `mvn spring-boot:build-image` (buildpacks) is an acceptable alternative.
- Port binding: `server.port=${PORT:8080}` in configuration. Render injects `PORT`; on Fly, set `internal_port = 8080` (and keep `force_https = true`) in `fly.toml`. Spring Boot does not read `PORT` on its own.
- This amends §20: compose gains an optional app service for running the production image locally; `mvn spring-boot:run` remains the default inner loop.

### 35.3 Configuration

- All environment-specific values come from environment variables: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and `PORT` (§35.2).
- The platform dashboard shows a libpq-style `postgres://` connection string; it is **not** a JDBC URL. Compose `SPRING_DATASOURCE_URL` by hand: `jdbc:postgresql://<host>:5432/<db>`.
- No secrets in the repository. Local defaults stay in `application.properties`; production values live only in the platform dashboard.

### 35.4 Schema management

**Adopt Flyway before deploying; migrations become the single source of truth for every environment.**

- `schema.sql` is converted to `V1__init.sql` and then deleted — no parallel schema file to keep in sync. Local dev, Testcontainers tests, and production all initialize via Flyway, so the integration-test suite exercises the same mechanism production uses. This changes the test bootstrap and must land with the test configuration updated in the same commit.
- Spring Boot 4 specifics: requires `org.springframework.boot:spring-boot-starter-flyway` (the Boot-3-era "just add flyway-core" recipe silently no-ops) plus `org.flywaydb:flyway-database-postgresql`.
- Before first deploy, verify the platform's Postgres major version is supported by the Boot-managed Flyway version (version skew here fails at startup).
- This upgrades §30's "Flyway if setup is quick" into a requirement for the hosting stretch goal.

### 35.5 Data posture (decided): public demo, ephemeral data

The hosted instance is a **demo, not a service**. This resolves the tension between "usable by real friend groups" (§5.1) and hosting third parties' personal data (Pix keys are emails and phone numbers; under LGPD the operator of a public instance holding real third-party data would be a data controller with obligations — deletion path, privacy notice, retention policy — that an append-only schema with no DELETE endpoints cannot honor).

- Every page and the README state: **"Instância de demonstração — não insira chaves Pix reais."** The seeded demo group uses fictitious data.
- **All hosted data is wiped on a schedule (every 30 days).** This is the retention policy, the LGPD answer, the junk-data cleanup, and — on Render's free tier, where the free Postgres is *always deleted 30 + 14 days after creation* — an alignment with what the platform does anyway rather than a silent failure.
- `demo.sh` runs against the hosted instance create real rows; the periodic wipe covers them. No per-run cleanup needed.
- Real-world use by friend groups remains supported via self-hosting (`docker compose up`), which the README documents. The hosted URL is for demonstration.

### 35.6 Abuse controls and security preconditions

`POST /api/v1/groups` requires no credential, so a public URL invites bot traffic by design. Before the URL is public:

- **Per-IP rate limiting on all write endpoints** (servlet filter with bucket4j; no Spring Security required), plus hard caps: groups created per IP per day, participants and expenses per group. Request body size limited via Spring properties.
- **Invite tokens: minimum 128 bits of entropy** (e.g., 22+ base64url chars from `SecureRandom`). "Cryptographically secure source" alone (§22) permits a brute-forceable 6-char token; the entropy floor closes that.
- **Token-leak mitigations:** `Referrer-Policy: no-referrer` and `X-Content-Type-Options: nosniff` headers on all responses; `noindex` (robots.txt + `X-Robots-Tag`) on group pages; templates load zero external resources. Note: platform edge logs record full URLs including query strings — one reason the UI moves the token out of URLs (§36.4).
- **HTTPS only** — automatic on Render; on Fly it is the `force_https = true` line, which must not be removed.
- **No actuator exposure:** Spring Boot Actuator is not currently a dependency; if it is ever added, only `/actuator/health` may be public (there is no Spring Security layer to protect the rest).
- README states plainly: no real payments, invite-token access model, not production-grade authentication.
- Pix-key masking and no-plaintext-logging rules (§22–23) implemented, not just documented.

### 35.7 Cost expectations

- **Render free tier: US$0 for the web service** (with cold starts), but free Postgres survives only ~30 days. Steady state: Render basic-256mb Postgres at ~US$6–7/month, or keep US$0 by pairing the free web service with Neon/Supabase free Postgres.
- Budget ceiling: **US$10/month (~R$60)**. R$0 is achievable only via the external-Postgres route; the addendum's wipe-every-30-days posture (§35.5) makes either path safe.

### 35.8 Deployment mechanics and definition of done

- **Deploys are gated on CI:** platform auto-deploy-on-push is disabled; a GitHub Actions job triggers the platform's deploy hook only after `mvn verify` is green on `main`. (Render's default GitHub integration deploys on push regardless of Actions status — that default is explicitly rejected.)
- Definition of done:
  - A public HTTPS URL serves `GET /api/v1/ping`; `GET /` redirects to the README.
  - `demo.sh` accepts a base-URL override, sends a warm-up ping with retries (≥90 s total; free-tier cold start is ~60 s) before asserting, and runs green against the hosted instance.
  - The scheduled wipe (§35.5), rate limiting, and headers (§35.6) are in place and smoke-tested.
  - README links the live URL, shows the demo banner text, and states the security limitations and the self-hosting path.

---

## 36. Web UI Plan

### 36.1 Decision

Server-rendered **Thymeleaf** (`spring-boot-starter-thymeleaf`, available under that name for Boot 4), no JavaScript framework, no frontend build tooling. Vanilla JavaScript only where HTML cannot do the job (copy-to-clipboard). Escaping rules: `th:text` only; **`th:utext` and unescaped inline expressions are banned** — every rendered string is user input.

### 36.2 Scope

The §21 list **plus the §15.8 activity view** (stated as an addition, not smuggled in), as pages:

1. **Group page** (reached via invite URL): participants, balances, suggested payments, expense history.
2. **Forms:** create group, add participant, add expense with exact shares.
3. **Actions:** copy Pix key, mark suggested payment complete.

Pix keys render **masked** (`lu***@gmail.com`) per §22's guidance; the copy action copies the full value without displaying it (full display only on the payment-instruction view, where the payer needs it).

Out of scope: expense editing, participant removal, authentication, pagination. The append-only rules from §28 apply unchanged.

### 36.3 Language

All user-facing strings via Spring `MessageSource`. **Amendment to §21:** the pt-BR strings live in the *default* bundle `messages.properties`. A `_pt_BR`-suffixed file as the only bundle breaks for any non-pt-BR `Accept-Language` visitor (an English-locale recruiter would see `??key??` placeholders — resource-bundle fallback selects the suffix file only for pt-BR requests). Adding another locale later adds suffixed files on top of the default. "No hardcoded literals in templates" is a convention enforced by review, not by test.

### 36.4 Invite-token transport and CSRF (new decision)

The base doc defines token transport only for the API query string (§15.2). For the browser:

- Visiting the invite URL once **exchanges the token for an `HttpOnly`, `SameSite=Lax`, `Secure` session cookie and redirects to a token-free URL**. After that, no page URL, form, or link carries the token — keeping it out of browser history, `Referer` headers, platform access logs, and chat-app link-preview bots (which fetch pasted URLs and would otherwise render the group page, Pix keys included).
- Token validation moves with it: UI controllers resolve the group from the cookie via the same service-layer check the REST path uses (§18.3's token tests stay valid; the UI adds a cookie-resolution test).
- **CSRF stance:** `SameSite=Lax` blocks cross-site form POSTs in modern browsers; with no other credential and no Spring Security on the classpath, this is the accepted mitigation for a demo. Documented as a limitation alongside the invite-token model itself.

### 36.5 Idempotency in the browser (amends §28.5, upgrades §14.3)

- All forms use **Post/Redirect/Get**: a successful POST redirects to the group page, so refresh can never resubmit.
- Each rendered form embeds a fresh UUID in a hidden field as the idempotency key (§28.5 amended: header transport for API clients, form field for the UI — same server-side machinery).
- **Request hashing (§14.3) is upgraded from stretch goal to UI precondition.** Without it, back-button → edit fields → resubmit sends different content under the same key, and §14.1's contract silently returns the *first* expense — silent data loss disguised as success. With hashing, same key + different body → 409, surfaced as a pt-BR error.

### 36.6 Error handling (new)

- Validation failures re-render the form with the pt-BR message resolved from the API error `code` (§16's codes are the contract; the UI maps codes → `MessageSource` keys — never asserts or matches on message text).
- The stale-suggestion case is **normal use**, not an edge case: suggested payments go stale the moment any expense lands (§12.6), so "mark complete" on a stale page yields a 409. The UI handles it by redirecting to the group page with a flash message ("os valores mudaram — confira as novas sugestões") and freshly computed suggestions.

### 36.7 Layering

UI controllers call the same application services as the REST controllers. No business logic in UI controllers or templates. The REST API remains the primary interface; the UI is a client of the service layer.

### 36.8 Definition of done and budget

- Full loop in a browser: open invite URL → add participants with Pix keys → record an unequal expense → view balances → copy a Pix key → mark the repayment complete. Verified manually (a browser-automation test is not required for the stretch goal).
- All invariant and API tests still pass; UI adds template-rendering smoke tests and the cookie-resolution test (§36.4).
- **Budget: two focused days, assuming first-time Thymeleaf** (mirroring §24's no-prior-experience calibration; the §36.4–36.5 groundwork is why the estimate holds). If it overruns, cut in this order, re-scoping the DoD to what remains: (1) expense history, (2) create-group form (the API + demo.sh still covers creation), (3) add-participant form. The group page with balances, suggestions, copy, and mark-complete is the irreducible core — if that doesn't fit, the UI waits.
