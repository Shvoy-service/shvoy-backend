# SHVOY API Contract

Living reference for decisions the frontend and backend must implement identically. Each section is owned by the story that introduced it (noted below); update this doc as part of the story that changes the decision, not after the fact.

---

## Authentication

**Owner:** Cognito Integration story.

- Amazon Cognito is the source of truth for credentials. The backend never stores or validates a password.
- The frontend authenticates directly against Cognito (user pool + app client, no client secret) and sends the resulting **access token** as a Bearer token: `Authorization: Bearer <token>`.
- The backend validates the token as an OAuth2 resource server: signature via the pool's JWKS, issuer, expiry, and that the token's `client_id` claim matches SHVOY's app client (Cognito access tokens carry `client_id`, not the standard `aud` — don't assume `aud` is present or checked).
- A valid Cognito token is necessary but not sufficient: the backend additionally requires an `ACTIVE` SHVOY profile linked to that identity (by `cognito_sub`). A deactivated user's still-valid Cognito token is rejected — deactivation revokes access without touching Cognito itself.
- Registration and invite-acceptance remain unauthenticated by design (no account/tenant exists yet at that point) — see `POST /api/onboarding/register`, `POST /api/onboarding/activate`, `POST /api/onboarding/invite/accept`.
- `local`/`test` environments run with authentication disabled entirely (no Cognito dependency) — not representative of dev/prod request shape.

---

## CORS

**Owner:** Cleanup Story 3 (CORS configuration).

Allowed origins are entirely config-driven (`shvoy.cors.allowed-origins`, comma-separated) — never hardcoded in source. Per environment:

| Environment | Origin(s) | Status |
|---|---|---|
| Local dev | `http://localhost:5173` | Confirmed — Vite's default dev-server port |
| Local preview | `http://localhost:4173` | Confirmed — `vite preview`, used by Playwright |
| Preview deploys (dev) | Cloudflare Pages, per-PR subdomains | Pattern known, exact domain not yet — will need a wildcard pattern, e.g. `https://*.pages.dev` |
| Production | Not decided | Depends on the eventual domain |

**Important caveat for local frontend dev:** the frontend's Vite dev server proxies `/api` requests, so they're same-origin from the browser's perspective and CORS never fires in that setup. `http://localhost:5173` in this config only matters if something bypasses that proxy; `http://localhost:4173` (the non-proxied preview server) is what actually exercises this config locally. **"Works locally" during normal frontend dev is not evidence the CORS config is correct** — the first real test of it is the first deployed environment (or a `vite preview` run).

Other decisions:

- **`allowedOriginPatterns`, not `allowedOrigins`** — needed to express the Cloudflare per-PR wildcard once its domain is known; `allowedOrigins` doesn't support wildcards.
- **Allowed headers:** `Authorization`, `Content-Type`, `X-Correlation-Id` (sent by the frontend on every request) everywhere; `X-Debug-Company-Id` additionally in `local`/`test` only — that header does nothing outside those profiles (see `TenantContextFilter`), so it isn't advertised as meaningful elsewhere.
- **`allowCredentials: false`** — the Cognito access token travels in the `Authorization` header, not a cookie, so the browser never needs to send credentials for this API. Deliberate, not the framework default; revisit explicitly if a cookie-based flow is ever introduced.
- Dev and prod have **no default** for `shvoy.cors.allowed-origins` — startup fails loudly if it's unset, rather than silently allowing nothing (or something unintended) while the real domains are still unknown.

---

## API discovery (OpenAPI / Swagger UI)

**Owner:** Cleanup Story 2 (springdoc-openapi setup).

- Spec (JSON): `GET /v3/api-docs` — reflects the live set of registered endpoints, not a hand-maintained document.
- Swagger UI: `GET /swagger-ui.html` (redirects to `/swagger-ui/index.html`).
- Reachable without a token in `local`/`dev`. **Disabled entirely in `prod`** (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`) — no API surface exposed publicly once internet-facing.
- Dependency: `springdoc-openapi-starter-webmvc-ui` **2.9.0** — the 2.x line targets Spring Boot 3.x; springdoc's 3.x line targets Spring Boot 4 and must not be used while this project is on Boot 3.5.
- Frontend type generation should point at `/v3/api-docs` in `dev` (or a locally-run instance), not a static checked-in copy — it's always current by construction.
- Endpoint-level `@Operation`/`@Schema` annotations are not yet added beyond what Spring MVC/validation annotations imply automatically — incremental, not blocking.

---

## Error response format

**Owner:** Cleanup Story 1 (standardised API error response format).

Every error response across the API — validation, not-found, conflict, forbidden, and authentication failures alike — uses the same JSON body:

```json
{
  "code": "DUPLICATE_EMAIL",
  "status": 409,
  "message": "Email already registered: alice@example.com",
  "timestamp": "2026-08-10T09:15:32.123Z",
  "path": "/api/onboarding/register"
}
```

- **`code`** — the stable, machine-readable contract. The frontend maps its own UI copy from `code`, never from `message`. Once a code is published here, its meaning doesn't change; a new failure case gets a new code rather than reusing an existing one.
- **`message`** — human-readable, for debugging/logs only. Wording can change at any time without notice.
- **`status`**, **`timestamp`**, **`path`** — informational, safe to display or log but not to branch logic on (branch on `code`).

### Code catalogue

| Code | HTTP status | Meaning |
|---|---|---|
| `DUPLICATE_EMAIL` | 409 | Email already registered/invited (self-registration or team invite) |
| `DUPLICATE_SUPPLIER` | 409 | A supplier with this name (case-insensitive) already exists in the caller's company |
| `LAST_ACTIVE_ADMIN` | 409 | Change would leave the company with zero active admins |
| `INVALID_INVITE` | 404 | Registration/invite token is unknown, expired, or already used — kept deliberately generic; doesn't distinguish which, to avoid leaking token state to an unauthenticated caller |
| `NOT_FOUND` | 404 | Resource doesn't exist, or exists in a different company (cross-tenant access returns the same `NOT_FOUND` as a genuine absence — see Multi-tenancy below — never a distinct code) |
| `VALIDATION_ERROR` | 400 | Request body failed `@Valid` constraints, or wasn't parseable JSON |
| `FORBIDDEN` | 403 | Authenticated, but the caller's role doesn't permit this action |
| `UNAUTHENTICATED` | 401 | No valid Cognito token, or the token's identity has no active SHVOY profile |

This table is the contract — extend it here first when a new failure case needs its own code.

### Multi-tenancy and `NOT_FOUND`

Accessing another company's data returns the same `404`/`NOT_FOUND` as a genuinely missing resource — never a distinct code or a `403` — so a response can't be used to probe whether a resource exists in another tenant.

---

## Suppliers

**Owner:** Story 3.2 (Supplier CRUD endpoints).

- `GET /api/suppliers` defaults to **active suppliers only**, sorted by name (case-insensitive). Pass `?includeInactive=true` to see deactivated ones too.
- No pagination yet — deliberately out of scope for the pilot's supplier counts (see Story 3.2). Revisit if/when supplier lists actually grow large enough to need it.
- Supplier `name` is **unique per company, case-insensitively** — creating or renaming into a name that collides (ignoring case) with another active-or-inactive supplier in the same company returns `DUPLICATE_SUPPLIER`/`409`. Enforced at the application level (case-insensitive) with a case-sensitive DB unique index as a race-safety-net only (see `V10__add_supplier_name_uniqueness.sql`) — a narrow residual gap where two concurrent requests differing only in letter case could both succeed is accepted rather than reached for a Postgres-only expression index.
- Mutations (`POST`/`PUT`/`DELETE`) are restricted to `ADMIN`/`PURCHASING`; `GET` (list and single) is open to any authenticated role.
- Soft-delete only — `DELETE` sets `status=INACTIVE`, never removes the row, since price files and (later) POs will reference suppliers by id.

---

## Money

**Owner:** Cleanup Story 4 (money serialisation & rounding rule).

- **Wire format:** a string amount plus an explicit currency code — `{"amount": "1234.56", "currency": "USD"}`. Never a bare JSON number (floats can't represent decimal currency exactly, and different JSON parsers handle numeric precision differently across languages — a string sidesteps both).
- **Internal type:** `BigDecimal` server-side, never `double`/`float`, for every monetary value.
- **Rounding mode:** `HALF_EVEN` ("banker's rounding") — not `HALF_UP`. Ties round to whichever neighbor is even (`0.125` → `0.12`, `0.135` → `0.14`), rather than always rounding away from zero.
- **Rounding step:** each line-level amount is rounded to 2 decimal places the moment it's computed; totals are sums of already-rounded values, never a sum of full-precision intermediates rounded once at the end. This means displayed line items always sum to the displayed total — the alternative (round-only-the-final-sum) can be a cent off from what's shown.
- **Implementation:** `com.shvoy.Money` (record: `amount` + `currency`) is the one monetary type — every field that's money should be a `Money`, not a raw `BigDecimal`. Its compact constructor enforces scale-2/`HALF_EVEN` on construction, including on every result of `plus`/`multiply`, so the rounding-step rule above is structural rather than a convention each call site has to remember. Wire (de)serialisation to/from the string format is built in (`MoneyAmountSerializer`/`MoneyAmountDeserializer`) — no per-field `@JsonFormat` needed anywhere `Money` is used.

**Still open, deferred to Feature 3 (no monetary fields exist in the codebase yet, so these don't have a concrete case to resolve against):**
- **Currency scope** — is SHVOY single-currency (e.g. USD only) for the pilot, or does multi-currency need to actually work? `Money` carries a currency code either way (validated as a real ISO 4217 code), but no conversion logic exists, and none is planned until this is answered.
- **The ±2% tolerance boundary** — the PO/price-reconciliation matching rule that motivated this story in the first place. Needs to be spelled out concretely (which comparison, which fields) once Feature 3 exists, and a boundary test written against that actual rule — `MoneyTest` currently proves the rounding policy in isolation (see `roundsEachLineThenSumsRatherThanSummingRawAndRoundingOnce`), not that specific business rule.

---

## Dates and timestamps

**Owner:** Cleanup Story 5 (date field mapping & container-fill deadline timezone). **Deliberately on hold** — container-fill doesn't exist yet (Feature 3), so the one substantive call this story exists to make (the deadline's timezone) has no concrete logic to resolve it against. Pick this back up once Feature 3's container-fill work starts.

Rule already agreed: business dates are `LocalDate` (serialises as `yyyy-MM-dd`), real points in time are `Instant` (serialises as ISO-8601 UTC).

| Field | Type |
|---|---|
| Requested ETD | `LocalDate` |
| Confirmed ETD | `LocalDate` |
| Price-file validity date | `LocalDate` |
| Payment anchor date | `LocalDate` |
| All `created_at`/`updated_at` timestamps | `Instant` (UTC) |
| Container-fill decision deadline | `Instant` (UTC) — **timezone for display/evaluation not yet decided**, see below |

**Open decision:** the container-fill decision deadline is a genuine point-in-time, but which timezone it's *presented and evaluated* in (supplier's, company's, or UTC) isn't decided yet — the "confirmed by deadline" branch depends on this. **TBD**, to be resolved and recorded here before container-fill logic depends on it.

---

## Change log

- Cognito Integration story: added Authentication section.
- Cleanup Story 1: added error response format + code catalogue.
- Cleanup Story 2: added API discovery (OpenAPI/Swagger UI) section.
- Cleanup Story 3: added CORS section.
- Cleanup Story 4: decided wire format, BigDecimal, HALF_EVEN, round-each-line-then-sum; implemented `Money`. Currency scope and the ±2% tolerance rule remain open, deferred to Feature 3.
- Cleanup Story 5: held entirely, pending Feature 3's container-fill work — not started.
- Feature 3, Story 3.1: `Supplier` entity + tenant-scoped repository (no endpoints yet).
- Feature 3, Story 3.2: added Suppliers section — CRUD endpoints, `DUPLICATE_SUPPLIER` code, default list filter/sort.
