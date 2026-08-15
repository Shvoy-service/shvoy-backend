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

## Email delivery (the `EmailSender` seam)

**Owner:** Story 4.7, extracting what Story 2.3 (invites) had already been doing inline into a shared abstraction — `com.shvoy.EmailSender` (interface), `EmailMessage`/`EmailAttachment` (the message shape), `ConsoleEmailSender` (the only implementation so far).

- **No real email is sent anywhere in this codebase yet.** `ConsoleEmailSender` logs every message at INFO level instead of sending it — `to`/`subject`/`body` in full (deliberate, unchanged from 2.3's original behaviour: an invite/verification link is meant to be visible here, since there's no real inbox to check locally or in dev yet), but an attachment's **filename/size/content-type only — never its bytes**. Logging a PDF's raw content would be both useless and exactly the kind of thing a "never log anything sensitive" rule exists to prevent.
- **Two consumers today:** `InvitationService` (2.3 — invite/verification links, no attachment) and `PurchaseOrderSendService` (4.7 — the generated PO PDF as `EmailAttachment`). Both go through the same seam so the Notifications feature's eventual SES implementation swaps in once, not per-flow — same `interface`-now/`real implementation`-later principle as `IdentityProvider` (Cognito), see Authentication above.
- **Deliberately not touched by this story:** `RegistrationService`'s own verification-email log line (`"Verification link for {}: ..."`) is the same pattern (see `LogCapture`'s Javadoc, which already names both `RegistrationService` and `InvitationService`) but wasn't migrated onto `EmailSender` here — out of 4.7's scope, which named invites and PO-send as its two consumers, not registration. A natural third consumer whenever this seam is next touched, not an oversight.

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
- **Allowed headers:** `Authorization`, `Content-Type`, `X-Correlation-Id` (sent by the frontend on every request) everywhere; `X-Debug-Company-Id` and `X-Debug-User-Id` (the latter added Story 4.4) additionally in `local`/`test` only — neither header does anything outside those profiles (see `TenantContextFilter`), so neither is advertised as meaningful elsewhere.
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
| `DUPLICATE_SKU` | 409 | A SKU with this code (case-insensitive) already exists for this supplier |
| `AMBIGUOUS_PRICE_WINDOW` | 409 | A new SkuPrice's validity window can't be reconciled with a SKU's existing prices without guessing (backdated/duplicate start, an overlap, or a gap) — see SKU & price entry/upload below |
| `CURRENCY_MISMATCH` | 409 | A PO line resolved to a different currency than the PO's existing lines — see Purchase orders below |
| `PO_NOT_EDITABLE` | 409 | Attempted to mutate (add/edit/remove a line, set ETD, cancel) a PO that isn't `DRAFT` — see Purchase orders below |
| `PO_HAS_EXPIRED_PRICES` | 409 | Finalisation blocked: at least one line has no valid price as of today, and no (or an incomplete) override was supplied — see Purchase orders below |
| `PO_NOT_READY_TO_GENERATE` | 409 | Generation blocked: the PO has no lines, or no requested ETD set — see Purchase orders below |
| `PO_NOT_SENDABLE` | 409 | Send blocked: the PO hasn't been generated yet (still `DRAFT`) — see Purchase orders below |
| `SUPPLIER_MISSING_CONTACT_EMAIL` | 409 | Send blocked: the supplier has no contact email on file — see Purchase orders below |
| `PO_NOT_READY_FOR_PI` | 409 | Logging a PI blocked: the PO is still `DRAFT` — see PI reconciliation below |
| `PO_NOT_READY_FOR_INVOICE` | 409 | Logging an invoice blocked: the PO is still `DRAFT` — see Payments & three-way match below |
| `CREDIT_NOT_OPEN` | 409 | A credit ledger operation (apply / cancel) requires an `OPEN` entry, but it's already `APPLIED`/`CANCELLED` — see Payments & three-way match below |
| `INELIGIBLE_APPROVER` | 409 | Can't add this user to the approver pool: they're not an active `APPROVER` in the company — see Approver pool below |
| `APPROVER_COUNT_EXCEEDS_POOL` | 409 | The required sign-off count would exceed the active approver-pool size (set-count, or a removal that would drop below it) — see Approver pool below |
| `PI_NOT_AWAITING_APPROVAL` | 409 | Approve/reject attempted on a PI that isn't routed for approval (auto-confirmed, already approved/rejected, or still logged) — see Approval routing below |
| `NOT_IN_APPROVER_POOL` | 409 | Approving a price increase (the 2-of-N gate) requires being an active member of the approver pool — see Approval routing below |
| `SELF_APPROVAL_FORBIDDEN` | 409 | The PO creator or PI logger cannot approve their own PI (segregation of duties) — see Approval routing below |
| `ALREADY_SIGNED_OFF` | 409 | The same approver cannot supply more than one of the required 2-of-N sign-offs — see Approval routing below |
| `INVALID_STATUS_TRANSITION` | 409 | An illegal reconciliation status transition was attempted (e.g. `AUTO_CONFIRMED` → `ROUTED_FOR_APPROVAL`) — a defense-in-depth guard, see Status lifecycle & audit below |
| `LAST_ACTIVE_ADMIN` | 409 | Change would leave the company with zero active admins |
| `INVALID_INVITE` | 404 | Registration/invite token is unknown, expired, or already used — kept deliberately generic; doesn't distinguish which, to avoid leaking token state to an unauthenticated caller |
| `NOT_FOUND` | 404 | Resource doesn't exist, or exists in a different company (cross-tenant access returns the same `NOT_FOUND` as a genuine absence — see Multi-tenancy below — never a distinct code) |
| `VALIDATION_ERROR` | 400 | Request body failed `@Valid` constraints, wasn't parseable JSON, or failed a hand-checked rule raised via `ValidationException` (cross-field checks Bean Validation can't express, or a price file's row-level errors collapsed into one message — see SKU & price entry/upload below) |
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

## Payment terms

**Owner:** Story 3.3 (Payment terms). This story stores terms and defines the split rule; it does not calculate actual due dates against a real order — that's Feature 7.

- A supplier has at most one payment-terms record: `PUT /api/suppliers/{id}/payment-terms` sets or updates it (full-representation, same PUT-replaces-everything semantics as `SupplierRequest`); `GET /api/suppliers/{id}/payment-terms` retrieves it. A dedicated sub-resource, not folded into `SupplierResponse` — same reasoning as splitting `TeamController` from `CompanyProfileController`.
- `GET` before terms have ever been set returns `404`/`NOT_FOUND`, same as any other not-yet-existing sub-resource.
- **Fields:** `depositPercentage` (`BigDecimal`, 0–100 inclusive, fractional values allowed up to **1 decimal place** — `33.5` valid, `33.55` rejected as `VALIDATION_ERROR` — confirmed by the Product Owner, Consolidation ticket) is the only percentage stored. `balancePercentage` in the response is always `100 − depositPercentage`, computed on read — never an independent stored/validated field, so the two can't drift out of sync. `anchorEvent` is one of `BL`/`INVOICE`/`ARRIVAL`/`EX_FACTORY` (the fourth value added by the Consolidation ticket, matching Roadmap v2's anchor options); `daysOffset` is a non-negative integer.
- **Model:** a separate `payment_terms` table keyed directly by `supplier_id` (shared primary key, no generated id of its own), not inline columns on `suppliers` — `Supplier`'s own Story 3.1 Javadoc already committed to payment terms being a separate entity, same as prices/SKUs and discount tiers.
- Mutation (`PUT`) is `ADMIN`/`PURCHASING`-only; `GET` is open to any authenticated role — same role split as Suppliers.
- Tenancy: the supplier must belong to the caller's company; a cross-tenant supplier id (or a nonexistent one) returns `404`/`NOT_FOUND` on both `PUT` and `GET`.

### The split rule (deposit/balance allocation)

To split an order total by a supplier's terms: `deposit = round(total × depositPercentage / 100)` at `Money`'s standard scale-2/**HALF_EVEN** rounding (via `Money.multiply`, not a separate rounding rule — see Money below), then `balance = total − deposit` (via the new `Money.minus`), **not independently rounded**. This guarantees `deposit + balance` always equals the total exactly, with any odd remainder falling on the balance rather than the deposit. Implemented as `PaymentTerms#split(Money total)`; not wired to any endpoint yet — Feature 7 is the first real caller.

**Deposit precision — confirmed (Consolidation ticket):** fractional percentages are allowed, capped at 1 decimal place (`33.5%` valid, `33.55%` rejected). Storage already supported arbitrary decimals; the cap is enforced as a validation rule on write, no migration needed.

**Decision made on a recommended default, pending Product Owner confirmation:**
- **Remainder placement:** the balance absorbs the odd penny, not the deposit (deposit stays a clean rounded figure). One-line change (which side is derived) if the PO wants it the other way.

An isolated, low-cost-to-reverse choice confined to `PaymentTerms#split`.

---

## Money

**Owner:** Cleanup Story 4 (money serialisation & rounding rule).

- **Wire format:** a string amount plus an explicit currency code — `{"amount": "1234.56", "currency": "USD"}`. Never a bare JSON number (floats can't represent decimal currency exactly, and different JSON parsers handle numeric precision differently across languages — a string sidesteps both).
- **Internal type:** `BigDecimal` server-side, never `double`/`float`, for every monetary value.
- **Rounding mode:** `HALF_EVEN` ("banker's rounding") — not `HALF_UP`. Ties round to whichever neighbor is even (`0.125` → `0.12`, `0.135` → `0.14`), rather than always rounding away from zero.
- **Rounding step:** each line-level amount is rounded to 2 decimal places the moment it's computed; totals are sums of already-rounded values, never a sum of full-precision intermediates rounded once at the end. This means displayed line items always sum to the displayed total — the alternative (round-only-the-final-sum) can be a cent off from what's shown. The exact expressions, so there's no judgement call left for a reader to make:
  - **Unit price** is never rounded to `Money`'s 2dp scale before it's used in a multiplication — it stays at `UnitPrice`'s own 4dp precision (see below) all the way to the point it's multiplied by a quantity.
  - **Line total** = `unitPrice.multiply(quantity)` (`UnitPrice#multiply`, Story 4.3) — the raw 4dp-price × integer-quantity product, rounded **once**, HALF_EVEN, to `Money`'s 2dp scale, via `Money`'s own compact constructor. Never `round(unitPrice, 2dp).multiply(quantity)` — pre-rounding the price first would compound error at volume, the same reasoning that gave `UnitPrice` its own 4dp scale.
  - **Order total** = the sum of already-rounded 2dp line totals (`Money#plus`, chained), **not** a rounded sum of unrounded lines. `PurchaseOrderTotalsService#recompute` (Story 4.3) is the one place this runs; every caller reads totals from there rather than re-summing lines independently.
  - **Allocation (deposit/balance split):** `PaymentTerms#split(total)` (Story 3.3) computes `deposit = total.multiply(depositPercentage / 100)` — `Money#multiply` rounds the raw product HALF_EVEN to 2dp in the same step, no separate rounding call — then `balance = total.minus(deposit)`, **never independently rounded**. The balance is *derived*, not computed from its own percentage, which is what guarantees `deposit.plus(balance)` equals `total` exactly. **The remainder always lands on the balance**, unconditionally — there is no "largest remainder" comparison between the two parts, and thus no separate tiebreak rule to state: whichever way `Money`'s HALF_EVEN rounding takes the deposit, the balance absorbs whatever's left. (A generic N-way proportional split, if SHVOY ever needs one beyond this 2-way case, is a different algorithm and not yet built — don't assume this rule generalises past deposit/balance.)
  - **Negative amounts** (reachable via the credit ledger): `Money`'s compact constructor applies `BigDecimal#setScale(2, HALF_EVEN)` regardless of sign, and `RoundingMode.HALF_EVEN` is sign-symmetric — a tie rounds to whichever neighbour is even on either side of zero, e.g. `-0.125` → `-0.12`, `-0.135` → `-0.14` (mirroring `0.125` → `0.12`, `0.135` → `0.14` exactly). Arithmetic (`plus`/`minus`/`multiply`) has no separate negative-number code path — the same compact constructor runs either way.
- **Implementation:** `com.shvoy.Money` (record: `amount` + `currency`) is the monetary type for currency-minor-unit amounts (totals, deposits, balances) — every such field should be a `Money`, not a raw `BigDecimal`. Its compact constructor enforces scale-2/`HALF_EVEN` on construction, including on every result of `plus`/`minus`/`multiply`, so the rounding-step rule above is structural rather than a convention each call site has to remember. Wire (de)serialisation to/from the string format is built in (`AmountSerializer`/`AmountDeserializer`, shared with `UnitPrice` below) — no per-field `@JsonFormat` needed anywhere `Money`/`UnitPrice` is used.
- **`com.shvoy.UnitPrice` — the sibling type for per-unit prices (added Story 3.4):** same record shape, wire format, and `HALF_EVEN` rounding as `Money`, but fixed at **scale 4** instead of 2. Procurement unit prices routinely carry 4 decimal places (e.g. `1.4275`); rounding a unit price down to 2dp before multiplying by a large order quantity would compound into a real total-price error, so it's a distinct type rather than `Money` used at a different scale. `Money` stays fixed at 2dp everywhere it's used — nothing about this introduces a variable-scale `Money`.

**Currency scope — settled (Consolidation ticket, per Roadmap v2):** SHVOY's MVP is explicitly single-currency, **USD**. `Money`/`UnitPrice` still carry a currency code on every value (validated as a real ISO 4217 code) rather than assuming USD implicitly — no conversion logic exists, and multi-currency (see the Phase 2 section below) is deliberately out of scope for MVP. Every example/fixture in this doc and the test suite uses `USD` accordingly; a non-USD currency code is still technically accepted by `Money`/`UnitPrice`'s own validation (it only checks "is this a real ISO 4217 code", not "is this USD"), but nothing in the product assumes any currency other than USD is actually in play yet.

**Still open, deferred to Feature 3:**
- **The ±2% tolerance boundary — SETTLED and built (Story 5.4).** The rule that motivated the Money story is now concrete: a variance is within tolerance **if and only if `|variance| < tolerance`** (strictly less than — an **exclusive** boundary, so a variance of exactly 2.00% against a 2% tolerance routes to approval), comparing the 5.3-rounded (HALF_EVEN, 2dp) variance magnitude against a **per-account** tolerance (default 2%). The comparison is isolated in `ToleranceEvaluator` with a dedicated boundary test — see the Tolerance evaluation & auto-confirm subsection under PI reconciliation below for the full rule, and tell the frontend the boundary is confirmed **exclusive** (if they assumed `<=`, their borderline behaviour flips — a one-line change on their side).

---

## SKU & price model

**Owner:** Story 3.4 (SKU & price file model). Data model. Entry/upload endpoints are 3.5 (see below), discount tiers 3.6, carton/pack size 3.7, the price-resolution service 3.8.

- A `Sku` is a supplier's product code: `code` (required), optional `description`, `status` (active/inactive, soft-delete only, same pattern as `Supplier`). Per-supplier code uniqueness (`DUPLICATE_SKU`, case-insensitive) is enforced as of Story 3.5, same sequencing as `Supplier`'s own name-uniqueness constraint (landed with its CRUD endpoints, 3.2, rather than its entity, 3.1).
- **Price history, not a single current price:** a SKU has many `SkuPrice` records over time, each with a validity window (`validFrom` required, `validTo` nullable/open-ended), rather than one mutable current price. Deliberate — Feature 5's PO/price-file reconciliation needs to resolve "the price valid on the order's date", which a single-current-price model loses the moment a new price file supersedes an old one. Costs more now (multiple rows per SKU) but avoids a retrofit once Feature 5 depends on historical lookup. Resolving which price applies on a given date (including handling overlapping/superseding windows) is the price-resolution service's job (3.8), not this model.
- **In-date/expired status is derived, never stored:** `SkuPrice#isInDate(LocalDate asOf)` computes it from `validFrom`/`validTo` against a caller-supplied reference date — there's no stored flag that could drift out of sync with the dates.
- Unit price is `UnitPrice` (see Money above) — `NUMERIC(19,4)` + a currency column at the database level (`sku_prices.unit_price_amount`/`currency`), plain columns rather than a JPA-embedded `UnitPrice`, matching this codebase's existing no-embeddables/no-relationships style (see `Supplier`, `PaymentTerms`).
- Validity dates (`valid_from`/`valid_to`) are `LocalDate`/`DATE` — see Dates and timestamps below.
- `Sku` and `SkuPrice` both implement `TenantScoped`; a test proves Company A can't see Company B's SKUs or prices (`SkuTenantIsolationTest`, `SkuPriceTenantIsolationTest`).

---

## SKU & price entry / upload

**Owner:** Story 3.5 (price file entry & upload). Manual entry and bulk upload both write through the same `SkuService` methods, so the supersession rule below applies identically either way — a price file upload is not a distinct code path from adding prices by hand, just many rows of it.

- `POST /api/suppliers/{id}/skus` — creates a SKU with its first `SkuPrice` in one call (a SKU without any price isn't a meaningful state). `201`, body: `{"sku": SkuResponse, "currentPrice": SkuPriceResponse}`.
- `POST /api/suppliers/{id}/skus/{skuId}/prices` — adds a new `SkuPrice` version to an existing SKU. `201`, body: `SkuPriceResponse`.
- `PUT /api/suppliers/{id}/skus/{skuId}` — updates SKU-level metadata only (`code`, `description`, `status`) — **never** the price; price changes are always new versions via the endpoint above, never edits reachable from this one.
- `POST /api/suppliers/{id}/price-file` — bulk upload, `multipart/form-data` with a `file` part. `201`, body: `{"rowsProcessed": n, "s3Key": "..."}`.
- All four: tenant-scoped (cross-tenant/nonexistent supplier or SKU → `404`), mutation restricted to `ADMIN`/`PURCHASING`. No `GET`/list endpoints exist yet for SKUs or prices — out of scope for this story (not requested by its acceptance criteria); the create/update responses are the only way to read one back for now.

### The supersession rule

Adding a `SkuPrice` (manually or via a file row) never mutates an existing price's value — it's always a new row. How its validity window relates to the SKU's existing prices:

- If the SKU has an **open** row (`validTo IS NULL`) and the new price's `validFrom` is **after** that row's `validFrom`: **auto-close** the open row — set its `validTo` to the day before the new row's `validFrom` — then insert the new row. This is the ordinary "a later price supersedes the current one" case.
- Anything ambiguous is **rejected** with `AMBIGUOUS_PRICE_WINDOW`/`409` rather than guessed at:
  - the new `validFrom` isn't after the open row's `validFrom` (backdated or duplicate-start), or
  - the new window overlaps another existing row, or
  - there's no open row (every existing price is already bounded) and the new `validFrom` isn't **exactly** the day after the latest existing row's `validTo` (a gap, or an overlap).
- A SKU's very first price has nothing to reconcile against and is always accepted.

This produces a clean, contiguous, non-overlapping timeline that the price-resolution service (3.8) can read deterministically, without it having to arbitrate ambiguity itself.

### Bulk upload

- The raw file is stored in **S3** (`aws.s3.documents-bucket`, key `price-files/{companyId}/{supplierId}/{uuid}-{filename}`) **unconditionally, before any parsing or validation** — it's the audit trail for a rejected upload, same as an accepted one. A `PriceFileUpload` row (supplier, S3 key, row count) is recorded only for a **successful** upload — a failed attempt's audit trail is the S3 object itself plus the error response; a DB row for it wasn't judged worth the extra transactional complexity of surviving the same rollback that discards its rows (see the story's own scoping note).
- **All-or-nothing:** every row's fields are validated *before* any row is applied; if any row fails, the whole file is rejected (`VALIDATION_ERROR`) with every failing row's issue(s) joined into one message, `"Row <n>: <issue>; Row <n>: <issue>..."` (1-indexed by data row, not counting the header) — same convention `ApiExceptionHandler` already uses for multiple `@Valid` field errors. Rows are then applied inside one transaction; if a later row hits `AMBIGUOUS_PRICE_WINDOW` during apply (a conflict the pre-apply field validation can't see, since it depends on rows already applied earlier in the same file), the whole transaction rolls back — that failure surfaces as a plain `AMBIGUOUS_PRICE_WINDOW` response, not decorated with a row number.
- A row's SKU code decides create-vs-add-version: an unrecognised code (for that supplier) creates a new SKU; an existing one adds a price version to it.

**Two items settled as recommended MVP defaults, flagged for Product Owner confirmation:**

1. **Canonical CSV template** — no real-world SHVOY supplier price-file format was available to build against, so this story defines one: a fixed six-column header, exactly `sku_code,description,unit_price,currency,valid_from,valid_to` (any other header is rejected outright, `VALIDATION_ERROR`). `description` and `valid_to` may be blank; `unit_price` up to 4dp; `valid_from`/`valid_to` as `yyyy-MM-dd`. If suppliers' real files turn out to need arbitrary/varied formats, that's a column-mapping story ahead of `PriceFileParser`, not a change to it — parsing itself is `org.apache.commons:commons-csv`, added this story as the first CSV dependency in the project.
2. **Future-dated prices are out of scope.** The supersession rule above assumes a new price supersedes the current one *now*. A price file effective from a future date while the current price keeps running (scheduling a future window rather than superseding immediately) is a different rule shape this story doesn't implement — confirm with Product Owners whether it's a real SHVOY scenario before building it.

### A latent gap this story closed

`UnitPrice`'s (and `Money`'s) compact constructor throws a raw `IllegalArgumentException` for an invalid currency code — nothing maps that to `VALIDATION_ERROR` automatically, so any endpoint that builds one from untrusted input must catch it explicitly (see `SkuService`'s currency handling) or risk an uncaught `500`. No earlier story's endpoints ever accepted a currency from a real request (3.3 has no `Money` field; 3.4 had no endpoints), so this was unreachable until now — worth remembering for any future endpoint that accepts a currency code directly.

---

## Discount tiers

**Owner:** Story 3.6 (Discount tiers). *Applying* a tier to resolve the right price for a given order quantity is the price-resolution service's job (3.8) — this story only defines, validates, and stores them.

- A `DiscountTier` ("at quantity ≥ threshold, the unit price is X") attaches to a **`SkuPrice`**, not the `Sku` directly. Prices are already validity-windowed (3.4/3.5); tiers need to stay versioned alongside the specific price they modify, so when a new price file supersedes an old `SkuPrice`, its tiers travel with the new version, and Feature 5's historical reconciliation still sees the tiers that actually applied at the time — not whatever the current tier structure happens to be.
- `PUT /api/suppliers/{id}/skus/{skuId}/prices/{priceId}/tiers` — **full-replace**: the submitted list becomes the price's entire tier set (an empty list clears all tiers), same PUT-replaces-everything convention as `SupplierRequest`/`PaymentTermsRequest`. No per-tier CRUD. `GET` on the same path retrieves the current set, sorted by threshold ascending.
- Mutation is `ADMIN`/`PURCHASING`-only; `GET` is open to any authenticated role. Tenant-scoped through the full supplier → SKU → price chain — a mismatch anywhere in that chain (wrong company, or a SKU/price that doesn't actually belong to the path's supplier/SKU) returns `404`/`NOT_FOUND`.
- No currency field on a tier or its request — a tier's currency is always its parent `SkuPrice`'s (tiers don't change currency), composed into the wire-format `UnitPrice` at read time rather than stored redundantly where it could drift out of sync.

**Two invariants enforced, both flagged as MVP defaults pending Product Owner confirmation:**

1. **Absolute unit price per tier, not a percentage discount off the base.** Simpler, unambiguous, and introduces no derived-rounding step (a percentage would have to define exactly when HALF_EVEN/4dp rounding applies to the computed price). Revisit only if suppliers turn out to actually quote volume pricing as a percentage rather than a flat per-unit price.
2. **Monotonically non-increasing price as quantity rises** — enforced across the *whole* chain, not just tier-to-tier: each tier's price must be ≤ the price for the quantity band below it, including the base `SkuPrice`'s own unit price for the lowest submitted threshold (the base price is effectively "the tier" for quantities below the first real one). A violation is a `VALIDATION_ERROR`, same as a duplicate threshold — both are treated as malformed input, not a conflict with existing state, since the whole submitted set is validated together before anything is written. This is almost always a true invariant for volume discounts (a data-entry error otherwise); revisit only if SHVOY needs to allow unusual tier structures.

**Not wired up yet:** tiers arriving through the 3.5 price-file upload path. 3.5's canonical CSV template (`sku_code,description,unit_price,currency,valid_from,valid_to`) has no tier columns — it was itself an invented MVP default with no real supplier price-file format to build against. Adding tier columns to that template, and having `PriceFileParser` populate `DiscountTier`s per row, is a follow-up once the real format (and whether it carries tiers at all) is confirmed — not implied by this story.

---

## Carton / pack size

**Owner:** Story 3.7 (Carton/pack size per SKU). Defines and stores the carton size and the multiple-check rule; the PO-side "ok / rounds to N" inline warning that consumes it is Feature 4's job.

- `carton_size` (units per carton) lives on **`Sku`**, not `SkuPrice` — the opposite attachment point from discount tiers (3.6). It's a physical property of how the product is packed, not something a new price file should ever change, so it isn't versioned with price. Nullable: a SKU without one simply isn't subject to the carton-multiple check (sold loose) — the permissive default, confirmed rather than forcing every SKU to declare one.
- Folded into the existing SKU management endpoint rather than a new sub-resource: `PUT /api/suppliers/{id}/skus/{skuId}` (3.5) now also accepts `cartonSize` alongside `code`/`description`/`status`. Same tenancy/role rules as the rest of that endpoint (`404` cross-tenant, `ADMIN`/`PURCHASING`-only). A supplied value must be a positive integer (`VALIDATION_ERROR` otherwise); omitting it leaves the SKU without a carton constraint.
- **The reusable rule**, exposed as instance methods on `Sku` (same shape as `SkuPrice#isInDate`/`PaymentTerms#split` — a pure derived-rule method living on the entity it's about, for Feature 4 to call directly rather than reimplementing):
  - `isCartonMultiple(int quantity)` — `quantity % cartonSize == 0`; always `true` when there's no carton size.
  - `nearestCartonMultiple(int quantity)` — the carton multiple *mathematically nearest* to `quantity`, not necessarily the one above it. E.g. carton size 10, quantity 12 → rounds to **10** (2 away) rather than 20 (8 away), even though that's fewer units than were asked for; ties round up. Returns `quantity` unchanged when there's no carton size.

**Worth flagging:** "nearest" is implemented literally as the story specifies (and as its acceptance criteria names it) — mathematically closest, which can round *down* below the requested quantity (the 12→10 case above). An "always round up to the next full carton" rule would behave differently and arguably reads more naturally for a purchase order (never under-supplying what was asked for). Not changed without confirmation, since the story text and acceptance criteria both explicitly say "nearest" — but worth a quick Product Owner sanity check before Feature 4 builds real PO validation on top of it, since the two interpretations disagree whenever the requested quantity is closer to the multiple below it than the one above.

---

## Price resolution — the Feature 3 → Feature 4/5 contract

**Owner:** Story 3.8 (Price resolution service), the final story of Feature 3. This is the one piece Feature 4 (PO creation) and Feature 5 (PI reconciliation) actually call — everything else in Feature 3 built the data this turns into an answer. Written down explicitly here, in full, so the result shape is a documented contract those features bind to rather than a decision that only ever lived in chat (the same gap that hit the error codes and rounding rule earlier in this project).

**`PriceResolutionService#resolve(supplierId, skuId, quantity, asOfDate)`** — `@NamedInterface("price-resolution")`, alongside its result type, so other modules can depend on both directly (same pattern as `onboarding.domain.Role`). Read-only, side-effect-free, and deterministic: the same four inputs always produce the same result, so Feature 5 can re-run a resolution for a past order date and reproduce the price that was actually quoted at the time. `asOfDate` is a required parameter with no "today" default — prices are historical, so a resolution without a date is meaningless.

Also reachable as `GET /api/suppliers/{supplierId}/skus/{skuId}/price-resolution?quantity=N&asOfDate=yyyy-MM-dd` — a pure read, so (unlike every mutating endpoint in this module) there's no `ADMIN`/`PURCHASING` restriction, just authentication. Tenant-scoped through the supplier → SKU chain (`404` cross-tenant/nonexistent, same as everywhere else); a non-positive quantity is `VALIDATION_ERROR`.

### The result shape (`PriceResolutionResult`)

| Field | Type | Meaning |
|---|---|---|
| `priceFound` | `boolean` | **The stable way to detect "no valid price for this date."** Never a thrown exception — a SKU genuinely having no price covering the as-of date is an expected resolution outcome, not a fault — and never a silent fallback to some other price. Feature 4 drives its "price file expired — blocks submit until overridden" behavior directly off this. |
| `skuPriceId` | `UUID`, nullable | The resolved `SkuPrice` version — null when `priceFound` is `false`. Kept for reconciliation traceability (Feature 5). |
| `unitPrice` | `UnitPrice`, nullable | The resolved 4dp unit price + currency — the tier price if one applied, otherwise the base `SkuPrice` price. Null when `priceFound` is `false`. |
| `appliedTierThreshold` | `Integer`, nullable | The threshold of the tier that applied, or **null when the base price applied** (no tier, or quantity below the lowest threshold) — that null/non-null distinction is exactly what Screen 3's "discount tier applied" indicator needs. |
| `asOfDate` | `LocalDate` | Echoes the date resolution was performed against. |
| `cartonValid` | `boolean` | From `Sku#isCartonMultiple` — always `true` when the SKU has no carton size. Populated **regardless of `priceFound`**: carton size lives on the SKU (3.7), not the price, so it doesn't depend on price resolution succeeding. |
| `adjustedQuantity` | `int` | From `Sku#nearestCartonMultiple` — always populated (equal to the requested quantity when already valid, or when there's no carton size). Never reimplemented here: this service calls the one shared carton rule from 3.7 rather than duplicating it, so the still-open "nearest vs. round-up" Product Owner question (see Carton/pack size above) has exactly one place to change when it's answered. |
| `everPriced` | `boolean` (added Story 4.5) | When `priceFound` is `false`, distinguishes *why*: `true` means the SKU has at least one `SkuPrice` row somewhere in its history (a price that's **expired** — a last-known value exists) versus `false`, meaning the SKU has **never** had a price (nothing to fall back on). Irrelevant when `priceFound` is `true`. Story 4.5's override flow needs this distinction — see Expired-price handling & override below. |

### Resolution logic

1. **Price:** the `SkuPrice` whose validity window contains `asOfDate`, via the existing `SkuPrice#isInDate` (3.4) — not reimplemented. The 3.5 supersession rule keeps a SKU's prices non-overlapping, so normally at most one matches.
2. **Tier:** from that price's tiers, the **highest threshold ≤ quantity** (tiers are monotonically non-increasing per 3.6, so the highest applicable threshold is always the correct price); no match means the base price applies.
3. **Carton:** independent of the above — see the table.

### Defensive handling: overlapping windows

Two `SkuPrice` rows matching the same `asOfDate` should never happen given 3.5's guards, but resolution doesn't assume the invariant always holds. If it ever does happen, the row with the **latest `validFrom`** wins (deterministic, not arbitrary), and a `log.warn` fires — since more than one match means the non-overlapping supersession invariant was violated somewhere upstream, which is worth knowing about, not silently papering over.

### Explicitly out of scope here

Multi-currency conversion/comparison (the resolved price simply carries its `SkuPrice`'s currency; MVP is single-currency USD — see Money above — and cross-currency handling belongs to Feature 5's reconciliation scope, now documented in the Feature 5 section below). The Screen 3 "blocks submit until overridden" UI behavior and any PO logic (Feature 4 — this service only supplies the `priceFound`/carton signals that behavior reads). Any price mutation (this service never writes).

---

## Purchase orders

**Owner:** Story 4.1 (PO data model), 4.2 (line pricing & validation), 4.3 (totals & money composition), 4.4 (creation & draft management). No generation/sending yet (4.6/4.7) — a PO never leaves `DRAFT` through any endpoint that exists so far.

- `PurchaseOrder` — tenant-scoped, one `supplier_id` (a PO is to exactly one supplier), a per-company sequential `po_number` (see below), `status` (`DRAFT`/`GENERATED`/`SENT`/`CANCELLED`, string not ordinal, modelled for clean extension by later features — `CANCELLED` added by 4.4, a draft-only soft-delete terminal state), nullable `requested_etd` (`LocalDate`), `created_by` (plain `UUID` referencing `users.id`, no JPA relationship — same flat-column convention as `Supplier`/`Sku`).
- `PurchaseOrderLine` — tenant-scoped, linked to its `PurchaseOrder` and a `sku_id`, `line_number` (stable display order, assigned sequentially by 4.4's add-line endpoint) and `quantity`, plus the **price snapshot** fields below.

### The price-snapshot principle

A PO line stores the price it was created with — `unit_price_amount` (4dp), `currency`, `applied_tier_threshold`, `line_total_amount` (2dp) — never a live reference to `SkuPrice` re-resolved on read. Once a PO is raised at an agreed price, that price is fixed regardless of later price-file updates; this is also what makes Feature 5 reconciliation meaningful — it compares a PI against the price the PO actually carried, not whatever 3.8 would resolve to today. `applied_tier_threshold` mirrors `PriceResolutionResult`'s field of the same name and meaning: it's a copied marker (null = base price applied), not a foreign key to a `DiscountTier` row, since a full-replace tier update (3.6) could delete the row it would have pointed to.

`line_total_amount` stays nullable with no mutator yet — that's 4.3's job. `PurchaseOrderLine#getUnitPrice()`/`#getLineTotal()` compose `UnitPrice`/`Money` from the snapshot columns, returning null until they're set — same convenience-view pattern as `SkuPrice#getUnitPrice`.

### Line pricing (Story 4.2)

`PurchaseOrderLinePricingService#priceLine(line)` is the logic 4.4's create/edit endpoints call — not an endpoint itself yet. Given an already-persisted line, it derives the PO's supplier from `line.purchaseOrderId` (never a separately-supplied parameter — one source of truth, not a caller-trusted one), calls `PriceResolutionService#resolve` (3.8) as of **the current date** (the draft date — a PO being raised now wants the price valid today), and applies the whole result in one shot via `PurchaseOrderLine#applyPriceResolution`, which also sets four more columns beyond the original snapshot fields:

| Field | Meaning |
|---|---|
| `price_found` | Null = never priced yet; `false` = priced, but no valid price covered `priced_as_of_date` (expired/absent price file — carried as a flag, not silently dropped or zero-priced; *blocking* on it is 4.5's job); `true` = `unit_price_amount`/`currency`/`applied_tier_threshold` are populated. |
| `priced_as_of_date` | The date resolution was run against — recorded regardless of `price_found`, for traceability. |
| `carton_valid` / `adjusted_quantity` | From `Sku`'s 3.7 rule via 3.8 — set **regardless of `price_found`**, since carton validity doesn't depend on pricing succeeding. `adjusted_quantity` inherits whatever the pending carton-rounding-rule Product Owner answer turns out to be automatically, since this story consumes the one shared rule rather than reimplementing it. |

**Re-resolving at generation:** a PO drafted today but generated/sent days later should reflect the price valid when it's actually finalised, not the stale draft-time price — so 4.6 re-resolves and re-snapshots at generation time. This story's resolution is the working-draft preview in between, not the final locked price.

**Validation, mostly enforced by reuse rather than new checks:** quantity must be positive (`VALIDATION_ERROR`) — checked explicitly here even though `PriceResolutionService` already checks it too, deliberate defense-in-depth for a caller that might reach this service some other way. A SKU that doesn't belong to the PO's supplier surfaces as `NOT_FOUND` **for free**, via `PriceResolutionService`'s own ownership chain — not reimplemented in this service. **New in this story:** a single-currency-per-PO rule — a line that resolves to a different currency than the PO's other already-priced lines is rejected (`CURRENCY_MISMATCH`/409). Originally flagged as a decision pending confirmation it fits how SHVOY's suppliers actually quote; now grounded rather than assumed — Roadmap v2 confirms MVP is single-currency USD (see Money above), so this rule matches the real constraint, not just a simplifying guess. The harder multi-currency question (a PI arriving in a different currency than its PO) still belongs to Feature 5 reconciliation — see that section below.

### Totals & money composition (Story 4.3)

The first place the Money contract's composition rules (see Money above) run for real rather than existing only as documented contract.

- **Line total:** `PurchaseOrderLine#applyPriceResolution` (4.2) computes it in the same call that snapshots the unit price — `UnitPrice#multiply(int quantity)` multiplies the **full 4dp unit price** by the integer quantity and rounds the raw product **once**, to 2dp HALF_EVEN, via `Money`'s own compact constructor. The unit price is never pre-rounded to 2dp before multiplying — doing so would compound error at volume, the same reasoning that gave `UnitPrice` its own 4dp scale in the first place (3.4).
- **Order total:** `PurchaseOrderTotalsService#recompute` sums the PO's lines' already-rounded 2dp totals via `Money#plus` — **never** a rounded sum of unrounded line values. This is what guarantees a human adding up the displayed line totals gets exactly the displayed order total. Unpriced lines (`price_found` not `true`) don't contribute. A PO with no priced lines has a null order total — never a fabricated zero.
- **Deposit/balance split:** computed here too, not deferred to Feature 7 — the total is already at hand, and reconciling `deposit + balance == orderTotal` is a money-composition concern like the rest of this story. Reuses `PaymentTerms#split` (3.3) unchanged via a new cross-module surface, `PaymentTermsService#trySplit(supplierId, total): Optional<PaymentSplit>` — `@NamedInterface("payment-terms")`, same pattern as `PriceResolutionService`/`PriceResolutionResult` (3.8), so `purchaseorders` never reaches into `PaymentTerms`/`PaymentTermsRepository` directly (caught by `ModularityTests`, the Spring Modulith boundary check, on first attempt). Deposit rounded HALF_EVEN, balance absorbs the remainder. If the supplier has no payment terms configured, `trySplit` returns empty and deposit/balance are left null (same "don't fabricate a number" principle as the order total), not computed against a default. Feature 7 computes the *due dates* from the anchor event/days offset; the *amounts* live here so 4.6's PO document can show the deposit/balance breakdown.
- **Recomputation:** `PurchaseOrderTotalsService.recompute` is the one reusable, deterministic, side-effect-free place totals are composed — 4.4, 4.6, Feature 5, and Feature 7 must all read totals from here, never re-sum lines independently. Currently wired into `PurchaseOrderLinePricingService#priceLine`, so pricing a line immediately recomputes the PO's stored order total/deposit/balance; 4.4 must call it again after any line addition/removal it implements, so stored totals never go stale relative to the lines.
- All amounts stay `BigDecimal`, HALF_EVEN, correct scale (order/deposit/balance 2dp, matching the PO's `currency` column — set once, when the first line is priced) — serialised as string + currency per the contract, never a bare JSON number.

### PO number generation

Per-company sequential, `PO-0001` style (`PoNumberGenerator`) — matches the wireframes and what users expect to reference a PO by, unlike a UUID-derived code. Unique **per company** (`V18`'s composite index on `company_id, po_number`), not globally — two different companies each having their own "PO-0001" is correct, not a collision.

Race-safe under concurrent claims for the same company via a `SELECT ... FOR UPDATE` lock on a dedicated `po_number_counters` row (one per company, owned by the purchaseorders module rather than added to `companies`, which belongs to onboarding) — same lock-based approach as `SkuService#lockSkuForPriceWrite` (see SKU & price entry/upload above). Ensuring that counter row exists and then locking/incrementing it run as two **sequential, non-nested** steps, deliberately: an earlier version isolated the row-creation step in its own `REQUIRES_NEW` transaction so a lost race there couldn't poison the claim's transaction (Postgres aborts an entire transaction after any failed statement — catching the exception and continuing in the *same* transaction only happens to work under H2, not for real against Postgres), but `REQUIRES_NEW` suspends rather than releases the caller's connection, so every concurrent caller needed two connections held at once — confirmed the hard way when 10 concurrent claims deadlocked a 10-connection pool outright. Running the two steps sequentially instead means each caller only ever holds one connection at a time.

### Creation & draft management (Story 4.4)

The workflow layer wiring 4.1/4.2/4.3 into real endpoints — introduces no new domain decisions of its own, only how the model/pricing/totals get exposed and guarded. Drawn boundary against 4.5: this story owns creating and editing a draft; 4.5 owns what happens when a draft with an expired-price line is *finalised*.

| Method | Path | Role | Notes |
|---|---|---|---|
| `POST` | `/api/purchase-orders` | ADMIN/PURCHASING | Creates a `DRAFT` for `{ supplierId }`. Cross-tenant/nonexistent supplier → `NOT_FOUND`. |
| `GET` | `/api/purchase-orders` | Any authenticated | List, tenant-scoped. Optional `?status=` filter (`DRAFT`/`GENERATED`/`SENT`/`CANCELLED`); omitted returns all of the caller's own POs. No pagination — pilot scale. |
| `GET` | `/api/purchase-orders/{id}` | Any authenticated | Full representation — lines and totals included. |
| `POST` | `/api/purchase-orders/{id}/lines` | ADMIN/PURCHASING | Adds a line (`{ skuId, quantity }`), assigns the next `line_number`, then calls `PurchaseOrderLinePricingService#priceLine` (which itself recomputes totals) — the pricing/totalling logic is invoked, never reimplemented here. |
| `PUT` | `/api/purchase-orders/{id}/lines/{lineId}` | ADMIN/PURCHASING | Full-replace of `{ skuId, quantity }`, then re-prices the same way as add. |
| `DELETE` | `/api/purchase-orders/{id}/lines/{lineId}` | ADMIN/PURCHASING | Removes the line, then calls `PurchaseOrderTotalsService#recompute` directly (no pricing step to trigger it this time). |
| `PUT` | `/api/purchase-orders/{id}/etd` | ADMIN/PURCHASING | Sets/updates `requestedEtd` (`{ requestedEtd }`, `LocalDate`). A past date is rejected (`VALIDATION_ERROR`) — today itself is accepted, only strictly-before-today is rejected. |
| `DELETE` | `/api/purchase-orders/{id}` | ADMIN/PURCHASING | Soft-cancel: sets `status` to `CANCELLED`. Not a hard delete. |

Every mutating endpoint returns the **full** `PurchaseOrderResponse` (status, ETD, totals, and the complete current line list) rather than a bare ack — a caller never needs a follow-up `GET` to see what its own write produced, same convention as `SkuWithPriceResponse`.

**Status guard — DRAFT-only mutation:** every mutation above (lines, ETD, cancel) requires `status == DRAFT`; attempting any of them against `GENERATED`/`SENT`/`CANCELLED` returns `PO_NOT_EDITABLE`/409, never a silent no-op. Enforced once, centrally, by `PurchaseOrderService#assertEditable`, reused by `PurchaseOrderLineService` rather than re-checked per endpoint.

**PO number assigned at creation**, not deferred to generation (4.6) — a pilot-scale default: an abandoned draft leaves a gap in the per-company sequence, an accepted cost in exchange for not needing a separate "assign a number" step later.

**`created_by`/current-user identity:** this story is the first to need "which user made this request," not just "which company" — added `CurrentUserContext` (mirrors `TenantContext`, same resolution point in `TenantContextFilter`: the JWT's `shvoy_user_id` claim in dev/prod, the new `X-Debug-User-Id` header in `local`/`test`). Unlike the company header, there is **no fallback default** for the user header — a caller needing the current user without supplying it fails loudly, consistent with how a missing tenant already behaves.

### Expired-price handling & override (Story 4.5)

Roadmap v2, verbatim: "A PO cannot be raised against an expired price without an explicit override + logged reason." This story builds that rule as **the gate 4.6 (generation) must call before finalising a draft PO** — `PurchaseOrderFinalisationGateService#checkFinalisationGate(purchaseOrderId, override)` — not an endpoint of its own. Same shape as 4.2/4.3: pure service-layer logic another story's controller invokes, no HTTP surface here yet.

**The check:** re-resolves every line via 3.8 as of **today** (never trusting the `priceFound`/`pricedAsOfDate` flags already snapshotted on the lines — a price valid when the line was drafted or last priced could have expired since; re-resolving is the only way to know for certain at finalisation time). Any line with no valid price blocks finalisation.

**The block:** `PO_HAS_EXPIRED_PRICES`/409, naming every affected line and — via `PriceResolutionResult#everPriced` (added this story, see Price resolution above) — distinguishing **expired** (the SKU has price history, none currently covers today) from **never priced** (nothing to fall back on) for each one. Message-only, not a structured field on `ErrorResponse`: same convention as every other `ConflictException` in this codebase (e.g. `AMBIGUOUS_PRICE_WINDOW`) — `ErrorResponse` has no structured per-item payload anywhere.

**The override:** blocked finalisation succeeds only when the caller supplies **both** a non-blank reason **and** a manual unit price for every blocked line (`ExpiredPriceOverrideRequest` — `reason` + a list of `LineOverridePrice`). Missing either leaves the block standing — there is no partial-override state. On success, the override is persisted as an **immutable audit record**, matching Roadmap v2's audit-trail standard ("every override is logged with user, timestamp, and reason — immutable history") rather than a log line: `PurchaseOrderPriceOverride` (who, when, the reason, the PO) plus one `PurchaseOrderPriceOverrideLine` per affected line (which line, the manual price supplied). Both are construct-only entities with no mutators at all — the same immutable-audit-record shape as `PriceFileUpload` (3.5), deliberately stronger than a log line since this is compliance-facing. Neither row is ever updated or deleted by application code.

**What price an overridden line carries — built against option (a), still open on the PO batch:** the override *requires* a manual price rather than reusing a stale one or letting the line through unpriced — the safe, correct default per the story, so an overridden PO always carries a concrete price for Feature 5 to reconcile against later. **Not yet answered by the Product Owner**; if the answer ever comes back as "reuse the most-recently-expired price instead," the change is contained to this service (the manual-price requirement in `assertOverrideCoversEveryBlockedLine`) rather than a rework — `PurchaseOrderPriceOverrideLine` already stores a real price regardless of where it came from, so the schema doesn't need to change either way.

**Two things explicitly deferred to 4.6, not forgotten:**
- **Role enforcement** (Roadmap v2/this story: override is `PURCHASING`/`ADMIN`-only) isn't built here — every role check in this codebase lives at `@PreAuthorize` on a controller (see `PurchaseOrderController`, `SkuController`, etc.), never inside a service, and this story doesn't introduce a first exception. **Now covered by 4.6's `POST .../generate` endpoint**, the same way 4.4's line-mutation endpoints already cover 4.2's `PurchaseOrderLinePricingService` invocation.
- **Currency consistency of an overridden line's manual price** against the PO's existing lines (the 4.2 `CURRENCY_MISMATCH` rule) is not checked here, and **still isn't checked by 4.6 either** — see that section's acknowledged-gap note. This service only validates that the manual price is a well-formed `UnitPrice` (positive amount, real ISO 4217 code).

Not built by this story (4.6's job, see below): the PO-status check (is this PO actually a `DRAFT`?), and the other finalisation preconditions (≥1 line, ETD set) — this gate checks only the expired-price rule, nothing else.

### Generation & document (Story 4.6)

Finalises a `DRAFT` PO into a durable, customer-facing PDF — the culmination of 4.1 (model), 4.2 (pricing), 4.3 (totals), 4.4 (draft management), and 4.5 (the expired-price gate). `POST /api/purchase-orders/{id}/generate` (`ADMIN`/`PURCHASING`, tenant-scoped, cross-tenant → `NOT_FOUND`) — request body is `{ "override": { "reason": ..., "lines": [...] } }` matching 4.5's `ExpiredPriceOverrideRequest`, entirely optional (omit for a clean draft with nothing to override). `GET /api/purchase-orders/{id}/document` retrieves the stored PDF (`application/pdf`, tenant-scoped, open to any authenticated role — reading isn't a mutation, same as `GET` on the PO itself); `NOT_FOUND` if the PO hasn't been generated yet.

**Preconditions, in order:**
1. `PurchaseOrderService#assertEditable` — the PO must still be `DRAFT` (`PO_NOT_EDITABLE`/409 otherwise, reused unchanged from 4.4).
2. This story's own two: at least one line, and a requested ETD set (`PO_NOT_READY_TO_GENERATE`/409, new this story — distinct from `PO_NOT_EDITABLE` since it's about the PO's *content* being incomplete, not its status).
3. `PurchaseOrderFinalisationGateService#checkFinalisationGate` (4.5) — the expired-price gate, invoked exactly as that story specifies, `override` passed straight through. Blocks with `PO_HAS_EXPIRED_PRICES`/409 unless every blocked line is covered by a complete override.

Any precondition failing blocks generation outright — **no partial document is ever produced**; the S3 write and the `DRAFT`→`GENERATED` transition both happen only after every check above has passed.

**Re-resolve and snapshot at generation time:** once the gate passes, every line is re-resolved via 3.8 as of **today** (the generation date, not the draft date — 4.2's own note: a PO drafted days ago should reflect the price valid when it's actually finalised) and re-snapshotted via `PurchaseOrderLine#applyPriceResolution`, exactly like 4.2's draft-time pricing. For a line the gate only passed via override (3.8 still can't resolve it — overriding doesn't change the underlying `SkuPrice` data), the freshly-resolved carton/asOfDate fields are kept but the price fields are substituted with the override's manual price (read directly from the `PurchaseOrderPriceOverrideLine` audit rows 4.5 persisted — no cross-module lookup needed, both stories live in `purchaseorders`). Totals are recomputed **once**, after every line is re-snapshotted, via `PurchaseOrderTotalsService#recompute` — not per-line, unlike 4.4's interactive add/edit flow, since generation touches every line in one pass.

**Acknowledged gap, not built:** the 4.2 single-currency-per-PO check (`CURRENCY_MISMATCH`) isn't re-run during this re-resolution pass, so a line that happens to re-resolve to a different currency than it had at draft time wouldn't be caught here. Considered out of this story's explicit scope (not an acceptance criterion) — a real edge case (a re-uploaded price file changing a SKU's currency) but rare enough, and cheap enough to add later if it ever surfaces, that building it speculatively wasn't worth it now.

**Locked after generation:** nothing new enforces this — `PurchaseOrderService#assertEditable` (4.4) already blocks every mutation path once `status != DRAFT`, so the just-written snapshot is frozen by construction, not by a separate check this story adds.

**The document itself — approach and rationale:** rendered via **Thymeleaf** (builds the document as XHTML from `PurchaseOrderDocumentData`, a clean record with nothing beyond what the template needs — never the JSON `PurchaseOrderResponse` shape, never the domain entities directly) piped into **openhtmltopdf-pdfbox** (converts that XHTML+CSS to PDF bytes). Two deliberately separate steps — data assembly, template, and PDF conversion each in their own place (`PurchaseOrderGenerationService` assembles the data; `templates/purchase-order-document.html` is the layout; `PurchaseOrderDocumentRenderer` is the only place the two meet) — so the layout can change, or a new template for a different document (dispute letters, compliance packs — future features) can be added, without touching pricing/business logic.

Library choice, verified before committing to it (per the story's own instruction):
- **openhtmltopdf-pdfbox** (`io.github.openhtmltopdf`, currently 1.1.73) — LGPL 2.1+, actively maintained (this is the community fork that picked up the original abandoned `danfickle/openhtmltopdf` project and moved it onto Apache PDFBox 3.x; the original `com.openhtmltopdf` coordinates are stale). LGPL is permissive enough for closed-source commercial use as an ordinary dependency.
- **Rejected: iText 7** — AGPL/commercial dual-licensed. Using it in a closed-source SaaS either forces buying a commercial license or open-sourcing SHVOY under AGPL — exactly the trap the story flagged as easy to reach for by habit (it's the best-known Java PDF name).
- **Rejected: JasperReports** — also GPL/commercial dual-licensed, plus a heavier XML/JasperStudio report-design workflow with no benefit over HTML/CSS for this MVP's content-focused needs.
- **Rejected: raw Apache PDFBox alone** (Apache 2.0, otherwise fine) — its low-level text/coordinate-positioning API would mix layout with data by construction, the opposite of the separation principle above.
- **Rejected: headless-browser rendering** (Playwright/Puppeteer-style) — not pure JVM, needs a browser binary in the container, meaningfully heavier ops footprint for CSS capability (flexbox/grid, JS) this document doesn't need. openhtmltopdf is CSS 2.1-only and wants well-formed XHTML — both non-issues for a Thymeleaf-generated template under our own control.

**Template content:** supplier name/country/contact email, PO number, requested ETD, generated date, a line-items table (SKU code/description, quantity, unit price, applied tier threshold, line total), and the order total/deposit/balance breakdown. Deliberately plain — content-focused and legible over polished, per the story's MVP framing.

**Cross-module surfaces added this story** (both `@NamedInterface("suppliers")`, same pattern as everything else purchaseorders reaches into another module for): `SupplierService#getSummary` → `SupplierSummary` (id/name/country/contactEmail) and `SkuService#getSummary` → `SkuSummary` (id/code/description) — narrow, purpose-built views for the document, deliberately not the full `SupplierResponse`/`SkuResponse` API shapes (which carry status/timestamps a customer-facing document has no business showing).

**Storage:** the PDF is stored in S3 (`aws.s3.documents-bucket`, the same bucket 3.5's price-file uploads use) under `purchase-order-documents/{companyId}/{poId}/{uuid}-{poNumber}.pdf` — same key-naming convention as `PriceFileUploadService#storeInS3`. The key is recorded on the PO (`document_s3_key`); regenerating isn't built (and shouldn't be necessary — the PO's snapshotted data means the same document *could* be reproduced deterministically if ever needed, but nothing here does so automatically).

**Generation metadata:** `generated_by` (`UUID`, references `users.id`) and `generated_at` (`Instant`) are set in the same `PurchaseOrder#applyGeneration` call that performs the `DRAFT`→`GENERATED` transition — both null until then, both included in `PurchaseOrderResponse`.

### Send to supplier (Story 4.7, final story of Feature 4)

`POST /api/purchase-orders/{id}/send` (`ADMIN`/`PURCHASING`, tenant-scoped, cross-tenant → `NOT_FOUND`) dispatches an already-generated PO to its supplier. No request body — everything it needs (recipient, document) is already on the PO. This closes Feature 4: create → price/validate → total → draft-manage → expired-price gate → generate document → send.

**Preconditions:**
1. Status must be `GENERATED` **or** `SENT` — a fresh `DRAFT` (never generated) is rejected with `PO_NOT_SENDABLE`/409. Deliberately *not* `PurchaseOrderService#assertEditable` (which would also reject `SENT`) — see the resend decision below for why `SENT` is allowed through here.
2. The supplier must have a contact email on file (`Supplier#contactEmail`, via the same `SupplierService#getSummary` surface 4.6 uses) — `SUPPLIER_MISSING_CONTACT_EMAIL`/409 otherwise. You can't send to nobody.

**What gets sent:** the PDF 4.6 already produced and stored — fetched via `PurchaseOrderGenerationService#getDocument` (S3 bytes, not re-fetched independently), attached to an `EmailMessage` addressed to the supplier's contact email, and handed to `EmailSender` (see the Email delivery section above) — `ConsoleEmailSender` logs it rather than actually sending it, until Notifications lands.

**Resend — confirmed allowed, not blocked.** The story offered two options: block re-send entirely (`PO_ALREADY_SENT`, simpler) or allow it (more forgiving, realistic). **Allowed** is what's built: "please resend the PO" is a normal real-world request, and blocking it outright would just push the user to work around it some other way. Every send — first or repeat — appends its own `PurchaseOrderSend` audit row (see below); a resend never re-prices, never re-resolves, never regenerates the document — the same locked PDF from 4.6 is dispatched again. `PurchaseOrder#markSent` is idempotent by construction (a no-op on the status itself once already `SENT`) rather than the caller branching on "is this a first send or a resend."

**Status transition:** `GENERATED` → `SENT` on the *first* successful send only (a resend leaves the status as `SENT`, unchanged).

**Audit trail:** `PurchaseOrderSend` — immutable, construct-only, same shape as `PurchaseOrderPriceOverride` (4.5)/`PriceFileUpload` (3.5): who sent it (`sent_by`), when (`sent_at`), the recipient email at the time (`recipient_email`), and a **snapshot** of the document reference that was actually sent (`document_s3_key`, copied from the PO at send time rather than a live reference — same snapshot-not-live-reference principle as a PO line's price). One row per send attempt; a resent PO has more than one.

`PurchaseOrderResponse` surfaces `sentBy`/`sentAt` for the **most recent** send only (null if never sent) — the full send history isn't exposed by any endpoint yet, only the latest, mirroring how `generatedBy`/`generatedAt` work.

---

## PI reconciliation

**Owner:** Story 5.1 (PI data model), 5.2 (log a supplier's confirmed PI), 5.3 (variance calculation), 5.4 (tolerance evaluation & auto-confirm), 5.5 (approval routing & the 2-of-N sign-off), 5.6 (approver pool configuration — built in `onboarding`, see below), 5.7 (status lifecycle & audit). **Feature 5 is complete.**

- `ProformaInvoice` — tenant-scoped, one `purchase_order_id` (a PO can receive more than one PI over time — see Cardinality below), a supplier-supplied `pi_reference`, `currency` (can differ from the PO's own — see Logging a PI below), `status` (string enum: `LOGGED`/`AUTO_CONFIRMED`/`ROUTED_FOR_APPROVAL`/`APPROVED`/`REJECTED` — only `LOGGED` has a real transition into it so far; 5.4-5.7 give the rest one), `active` (boolean — see Cardinality), `logged_by` (plain `UUID` referencing `users.id`, same flat-column convention as `PurchaseOrder#createdBy`).
- `ProformaInvoiceLine` — tenant-scoped, linked to its `ProformaInvoice` and a `sku_id` (correlated by SKU, not a direct PO-line FK — see Line correlation below), `line_number`, `confirmed_unit_price_amount` (4dp), `confirmed_quantity`. No currency column — inherited from the parent PI, same pattern as `DiscountTier` inheriting `SkuPrice`'s currency.

### Cardinality (Story 5.1)

A PO can receive more than one PI over time — a supplier re-issuing a corrected proforma is a real, expected scenario, so the model is one-PO-to-many-PIs rather than strictly one-per-PO. `active` identifies the current one: exactly one PI per PO is active at a time, and superseded PIs are kept, never deleted, for audit. `ProformaInvoice#supersede` (called by `ProformaInvoiceRecordingService` on the prior active PI, immediately before the new one is saved as active — Story 5.2) is the actual supersession step. No DB lock backs this invariant against concurrent double-submission for the same PO — an accepted, narrow gap, the same shape `SkuPrice`'s price-window supersession had before a later review added a `SELECT ... FOR UPDATE` lock there; worth the same treatment if this one is ever actually hit under load.

### Line correlation (Story 5.1)

`ProformaInvoiceLine.sku_id` correlates to a PO line by SKU, not a direct PO-line foreign key — real PIs don't always line up one-to-one with the PO (a supplier might split, merge, omit, or add a line). This assumes SKUs are unique within a PO, which the PO line model already guarantees. Unmatched lines either direction, and duplicate SKUs, are messy cases deferred to 5.3's matching logic to detect and surface — this model only makes the correlation possible.

### Logging a PI (Story 5.2)

| Method | Path | Role | Notes |
|---|---|---|---|
| `POST` | `/api/purchase-orders/{poId}/proforma-invoices` | ADMIN/PURCHASING | Records a PI (`{ piReference, currency, lines: [{ skuId, confirmedUnitPriceAmount, confirmedQuantity }] }`) against a PO. `PO_NOT_READY_FOR_PI`/409 unless the PO is `GENERATED` or `SENT` — a `DRAFT` has nothing final to reconcile against yet. Logging a second PI against the same PO supersedes the prior as active (see Cardinality above). |
| `GET` | `/api/purchase-orders/{poId}/proforma-invoices` | Any authenticated | Lists every PI logged against a PO — the active one and any it superseded — newest first. |
| `GET` | `/api/proforma-invoices/{id}` | Any authenticated | Fetches one PI with its lines. |

**"Record faithfully, judge later" — the validation posture this story exists to protect.** The PI is the supplier's document, not a claim to be checked against the PO at entry time. Validation here is well-formedness only: `piReference` non-blank, `currency` a real ISO 4217 code, each line's SKU exists **somewhere in the caller's company** (`SkuService#assertOwnSkuExists` — deliberately *not* scoped to the PO's own supplier, since a SKU that exists under a different supplier is a genuine discrepancy for reconciliation to surface, not an unknowable typo; only a SKU id that doesn't exist in the company at all is `NOT_FOUND`), unit price positive and ≤4dp, quantity a positive integer. Price, quantity, and currency **disagreement with the PO is recorded, not rejected** — that disagreement is exactly the signal reconciliation (5.3+) exists to process. Only structural nonsense (a SKU that doesn't exist at all, a malformed/negative value) is hard-rejected at entry.

**The post-log reconciliation trigger — a seam, not a build.** Logging is architected so the comparison (5.3/5.4) can run automatically right after a PI is recorded, without coupling the two together: `ProformaInvoiceRecordingService#recordPi` runs the actual write in its own transaction (a separate Spring bean, so its `@Transactional` boundary is real — not bypassed by same-class self-invocation, and without resorting to `REQUIRES_NEW`, which this codebase already hit a connection-pool deadlock with — see PO number generation above), and only once that transaction has committed does `ProformaInvoiceService#log` call `ReconciliationTriggerService#onPiLogged` — currently a stub that only logs, wrapped in a try/catch so a future failure there can never roll back or fail the logging request itself. 5.3 fills in the real comparison behind this same call.

**One entry point for manual entry and the future AI feed.** `ProformaInvoiceService#log` is the only place a PI actually gets recorded — the controller calls it with a request built from a human's form submission today; the roadmap's AI Document Intelligence layer (Feature 8) would call the exact same method with a request built from whatever it extracted from the supplier's document, converging on identical validation/supersession/trigger behaviour rather than a parallel path.

### Variance calculation (Story 5.3)

The three-way comparison at the heart of reconciliation — PO vs PI vs price-file formula — computed per line and **recorded, not judged**. This story produces a complete, stored comparison; it takes no tolerance or routing decision (that's 5.4, which reads what this writes). It runs automatically off 5.2's post-log seam (`ReconciliationTriggerService#onPiLogged` now delegates to `ReconciliationService#reconcile`), so logging a PI produces a comparison while the logging transaction still commits independently — a comparison failure never loses the logged PI.

| Method | Path | Role | Notes |
|---|---|---|---|
| `GET` | `/api/proforma-invoices/{piId}/reconciliation` | Any authenticated | The latest comparison recorded for a PI (header + per-line legs/variances/findings). `NOT_FOUND` if none was recorded (e.g. the comparison failed at log time — the PI is still logged). There is deliberately no "run reconciliation" endpoint — it runs on log. |

**The three legs**, per matched line: the **PO leg** (the price/quantity snapshotted on the PO line at generation — Feature 4, never a live re-resolve), the **PI leg** (the supplier's confirmed price/quantity, 5.1/5.2), and the **price-file formula leg** (3.8's `PriceResolutionService#resolve`, the independent "what should this cost" reference). Cross-module reads via a new `@NamedInterface("purchase-orders")` surface, `PurchaseOrderService#getReconciliationView` → `PurchaseOrderReconciliationView`/`PurchaseOrderReconciliationLine` (supplier, currency, generation date, and each line's snapshot) — the PO domain/entities stay internal, same minimal-contract discipline as everything else cross-module.

**The as-of date for the price-file leg — the PO's generation date.** 3.8 resolves as-of a date; this leg is resolved as of when the order was *raised* (the PO's `generatedAt` as a `LocalDate`, UTC), at the ordered (PO) quantity — so it reproduces what the PO leg's price should have been, making it a check on whether the PO was priced correctly at the time, rather than conflating "the PO was mispriced" with "the price file has changed since". Recorded on the reconciliation (`priceFileAsOfDate`) for auditability. Best-effort: if 3.8 can't resolve (no price for that date, etc.) the leg is recorded absent (`priceFilePriceFound: false`), never aborting the comparison — the headline PI-vs-PO variance doesn't depend on it.

**The variance — PI vs PO, signed, HALF_EVEN to 2dp.** `variance% = ((PI − reference) / reference) × 100`, where the reference leg is the **PO** ("did the supplier confirm a different price than we ordered at" — which is what the approval gate guards). Stored **signed**: positive is an increase, negative a decrease — the sign *is* the direction, load-bearing because Roadmap v2 gates price *increases* behind the 2-of-N approval (5.5) but not decreases (no separate direction field to drift; the read model surfaces `unitPriceVarianceDirection` derived from the sign). Rounded HALF_EVEN to 2dp **before** storage/comparison, so the stored, displayed, and compared values are the same number — which is what keeps 5.4's tolerance comparison deterministic and stops frontend/backend disagreement at the boundary. Isolated in `VarianceCalculator` (pure, deterministic, unit-tested without Spring).

**The variance basis — the last open Product Owner question, isolated in one constant.** Whether variance is computed on **unit price**, **line total**, or **both** decides what the reference/PI values are. Built as **unit price** (`VarianceCalculator.BASIS = UNIT_PRICE`, the recommended default): variance is on the per-unit price, with quantity tracked as its own separate comparison — keeping the tolerance/approval decision about *price* movement and stopping a quantity change from masking or manufacturing a price variance, matching Screen 4's separate Unit-price/Quantity rows. The basis lives in that one clearly-marked constant so "line total" is a one-line change; "both" would be a storage-shape change (two variances per line) and is deliberately not modelled yet. The basis used is recorded on every reconciliation (`varianceBasis`) so historical records stay interpretable if the default ever changes.

**Quantity comparison** is computed alongside — both an absolute difference (`quantityVarianceAbs`, PI − PO) for display and a signed percentage (`quantityVariancePct`) for tolerance consistency. It's currency-independent, so it's computed **even on a currency mismatch**, where the unit-price variance isn't. The price-file leg has no quantity (it's a price reference).

**Structural findings — detected and surfaced, not assumed away.** SKU-based correlation (the 5.1 decision) means the messy cases are findings, not silent drops, modelled as `ReconciliationLine`s with a `findingType`: `MATCHED` (the only type carrying a variance), `UNMATCHED_PI_LINE` (supplier added a SKU the PO lacks), `UNMATCHED_PO_LINE` (supplier omitted a PO SKU), `DUPLICATE_SKU` (same SKU more than once on a side — pairing can't be guessed, so it's flagged). 5.4 routes on these; this story only detects and records them.

**Currency mismatch — flagged, never converted.** A PI whose currency differs from the PO's sets `currencyMismatch` on the reconciliation and leaves each matched line's unit-price variance null (a cross-currency variance is meaningless without an FX rate — **no FX conversion is attempted**; that's Phase 2, and per the PO's answer this routes to approval as an exception in 5.4). Both legs' prices are still recorded in their own currencies for the approver, and quantity variance is still computed.

**Stored on every comparison, always.** Per the Product Owner's explicit ask, the variance is persisted on **every** matched line — including ones well within any tolerance that will auto-confirm (5.4) — for per-supplier drift trending. It's never computed-and-discarded on the pass path; cheap now, impossible to backfill later.

### Tolerance evaluation & auto-confirm (Story 5.4)

The decision the whole feature exists for: take 5.3's stored variance and decide the outcome — within tolerance → **auto-confirm**; outside → **route to approval**. Runs immediately after 5.3 on the same post-log seam (`ReconciliationTriggerService` → `ReconciliationService#reconcile` then `ToleranceEvaluationService#evaluate`, two steps in their own transactions so a comparison is durably recorded even if evaluation later fails). This story decides *auto-confirm vs route*; the **approval mechanics** (who approves, the 2-of-N gate, the increase/decrease asymmetry) are 5.5/5.6.

**The tolerance setting — per-account, configurable, default 2%.** Per the Product Owner: **one global setting per company** (not per-supplier, not per-SKU — deferred beyond MVP), stored in `tolerance_settings` (one row per company), with a built-in **2%** default applied when unset so reconciliation works before anyone configures anything. `GET/PUT /api/reconciliation/tolerance` reads/sets it (PUT is ADMIN-only; the response's `usingDefault` flags whether the built-in default is in effect). The effective value is resolved through the single `ToleranceService#resolveEffectiveTolerance` lookup, so a future move to per-supplier tolerance is an extension there, not a redesign of the call sites.

> **Doc conflict resolved:** Roadmap v2 said tolerance is "per company/supplier, e.g. ±3%"; the Product Owner's later, more specific answer is **per-account at 2%**, which is what's built. This contract is the source of truth — the roadmap's per-supplier/±3% wording is superseded and should be corrected there so the two don't sit contradicting each other.

**The comparison — EXCLUSIVE boundary.** A variance is within tolerance **if and only if `|variance| < tolerance`** — strictly less than. A variance of **exactly 2.00%** against a 2% tolerance is **outside** → routes. The comparison uses the variance **exactly as 5.3 rounded it** (HALF_EVEN, 2dp) — never re-rounded, never against a raw value — so the displayed and compared numbers are identical, which is what stops the frontend and backend disagreeing at the boundary. It's on the **magnitude** (absolute variance); the direction (sign) is retained for 5.5's asymmetric gate, not consulted here. **This single `<` is isolated in `ToleranceEvaluator.isWithinTolerance`** — deliberately not inlined into a compound condition where an edit could silently flip it to `<=`, and pinned by a dedicated boundary test (`ToleranceEvaluatorTest` + the `varianceExactlyAtToleranceRoutes` integration test). **Frontend note: the boundary is confirmed exclusive (`<`)** — a reconciliation screen that assumed inclusive (`<=`) has its borderline behaviour flipped; it's a one-line change, but only if they know.

**Whole-PI outcome, per-line detail.** Variance and findings are per line, but the **outcome is for the PI as a whole** — you don't part-confirm a supplier's document. A PI auto-confirms only when **all** of: no currency mismatch, no structural finding (every line `MATCHED`), and every matched line strictly within tolerance. **Any** of these routes the whole PI: a line at-or-outside tolerance, any structural finding (unmatched PI/PO line, duplicate SKU — a structurally mismatched PI shouldn't auto-confirm even if the matched lines are fine), or a currency mismatch (routed as an exception, **no FX auto-convert** — the cross-currency unit-price variance stays uncomputed; only quantity, which is currency-independent, is compared). The per-line detail is retained so the approver sees exactly which lines drove it.

Only the **price (unit-price) variance** gates tolerance — quantity is tracked as its own comparison (5.3's unit-price basis) but a quantity-only difference within price tolerance does **not** route on its own in the MVP, consistent with the tolerance decision being about price movement. (Flagged as the deliberate reading, not a silent gap.)

**Recorded outcome.** The `Reconciliation` gets `outcome` (`AUTO_CONFIRMED`/`ROUTED_FOR_APPROVAL`) and `toleranceApplied` (the % used — recorded because the account setting can change later, so a routing reason stays reproducible against the boundary actually applied), and the PI's own `status` is transitioned to match. **Auto-confirm is a system action** — no user actor; the audit of what/when/against-what-variance is the reconciliation record itself (richer immutable audit is 5.7). The routing **reasons** (`VARIANCE_OUTSIDE_TOLERANCE`/`STRUCTURAL_FINDING`/`CURRENCY_MISMATCH`, surfaced on the read model) are derived deterministically from the stored lines + `toleranceApplied`, not denormalised — nothing to drift. The comparison and outcome are read together via the same `GET /api/proforma-invoices/{piId}/reconciliation`.

**The tolerance test is symmetric; the (not-yet-built) approval path is directional — stated explicitly so the two don't get conflated.** `ToleranceEvaluator` compares `|variance|` against the tolerance, so a 2.5% increase and a 2.5% decrease are treated identically by 5.4 — both route, for the same reason (`VARIANCE_OUTSIDE_TOLERANCE`), via the same `<` on the same magnitude. What differs is what happens *after* routing, which is 5.5/5.6's job, not this story's: Roadmap v2 gates a price **increase** behind the 2-of-N sign-off, but a **decrease** doesn't need that gate — it still requires a human to act on the routed PI (nobody auto-confirms outside tolerance), just not the multi-approver sign-off. `VarianceDirection` (`INCREASE`/`DECREASE`/`NONE`, derived from the signed variance's sign, not stored separately) is what 5.5 will read to tell the two apart. Symmetric tolerance test, directional sign-off requirement — not "directional end to end."

**Worked examples** — unit-price basis, PO as reference, HALF_EVEN 2dp rounding applied to the variance *before* it is stored, displayed, or compared (never against a raw value), per-account tolerance at the built-in 2% default:

| PO unit price | PI unit price | Raw variance | Stored/compared variance | `< 2.00`? | Outcome |
|---|---|---|---|---|---|
| 2.0000 | 2.0398 | 1.99% | 1.99% | yes | Auto-confirmed |
| 2.0000 | 2.0400 | 2.00% | 2.00% | **no** (exclusive) | Routed — `VARIANCE_OUTSIDE_TOLERANCE` |
| 2.0000 | 1.9600 | −2.00% | −2.00% | **no** — magnitude `2.00` | Routed — same reason, decrease direction (no 2-of-N once 5.5 lands) |
| 100.0000 | 101.9950 | 1.9950% | **2.00%** (HALF_EVEN: 1.995 is an exact tie between 1.99 and 2.00; 2.00's last digit is even, 1.99's is odd) | **no** | Routed — the rounding step itself is what pushes this one across the boundary; this is the deliberate, shipped behaviour (round-before-compare), not a bug. A frontend comparing the *raw* 1.9950% against `2.00` would auto-confirm here and disagree with the backend. |
| 2.0000 | 2.0000 | 0.00% | 0.00% | yes | Auto-confirmed |

### Approver pool configuration (Story 5.6)

The named pool of people eligible for the price-increase sign-off, and the "N of pool" threshold — the configuration that underpins 5.5's 2-of-N gate (without a pool there's nobody to route a price increase to). **Built in the `onboarding` module, not `reconciliation`:** it's company/user governance (ADMIN configures it alongside roles and team membership), and validating that a member is an active `APPROVER` is then an internal check rather than a cross-module hop. 5.5 (in `reconciliation`) consumes it through `ApproverPoolService`'s `@NamedInterface("approver-pool")` surface.

- **Explicit membership, not role-derived.** The pool is an explicit list of named users (`approver_pool_members`), *not* simply "everyone with the `APPROVER` role" — the business rule says "named individuals" and Screen 4 shows a discrete list, so a company can have more `APPROVER`-role users than are named in the pool. Holding the `APPROVER` role is a **constraint** on membership (checked at add time), not what defines it: the role grants "can approve at all", pool membership grants "eligible for this specific gate".
- **Required sign-off count** (`approver_pool_settings`, one row per company, default **2** — the business rule's 2-of-3 example) is the "N". Minimum 1 (a single-approver pool is allowed but defeats the multi-party point — permitted, not encouraged); the meaningful ceiling is the pool-size rule below.

| Method | Path | Role | Notes |
|---|---|---|---|
| `GET` | `/api/onboarding/company/{companyId}/approver-pool` | Any authenticated | The required count + members, each joined to the live user with an `eligible` flag; plus `eligibleMemberCount` and `usingDefaultRequiredCount`. |
| `POST` | `…/approver-pool/members` | ADMIN | Add `{ userId }`. Cross-tenant/unknown user → `NOT_FOUND`; not an active `APPROVER` → `INELIGIBLE_APPROVER`; already a member → `VALIDATION_ERROR`. |
| `DELETE` | `…/approver-pool/members/{userId}` | ADMIN | Remove. Rejected (`APPROVER_COUNT_EXCEEDS_POOL`) if it would drop the active pool below the required count. |
| `PUT` | `…/approver-pool/required-count` | ADMIN | Set `{ requiredSignOffCount }` (≥1). Rejected (`APPROVER_COUNT_EXCEEDS_POOL`) if it exceeds the active pool size. |

Configuring is **ADMIN-only** — it's the governance control determining who can authorise price increases, so it belongs with the role that manages users/roles, not `PURCHASING` (who would be configuring their own oversight). Reading is open to any authenticated company user.

**The load-bearing validation: the required count must never exceed the active pool size** — otherwise the gate is an unpassable wall and no price increase can ever be approved. Enforced (`APPROVER_COUNT_EXCEEDS_POOL`) both when the count is set and when a member is removed. "Active" is computed **live** every time (a member who is currently an `ACTIVE` `APPROVER`), never snapshotted.

**Deactivation is handled at use time, not by mutating the pool.** Deactivating a user (2.6) or changing their role does **not** remove them from the pool — they stay listed but show `eligible: false`, and `ApproverPoolService#resolveEligibleApprovers` (the set 5.5 routes to) filters them out. This avoids surprising side-effects on deactivation; the trade-off is that the count-vs-size guard must reason about *active* members, which it does.

> **Flag for 5.5 (runtime robustness):** the set-count/remove guards keep the pool passable at *configuration* time, but they can't stop enough pool members being *deactivated* later that the required count can no longer be met. A price increase routed to a now-undersized pool would be stuck with no path to approval. **5.5 must detect and surface that at routing/approval time** (e.g. flag the PI as un-approvable rather than leaving it silently stranded), since 5.6's static validation alone can't prevent it.

### Approval routing & the 2-of-N sign-off (Story 5.5)

Takes a PI that 5.4 routed and lets the right people approve or reject it, applying the asymmetric rule. **Two things stack and must not be conflated:** *routing* (any out-of-tolerance / structural / currency PI goes to a human — 5.4, direction-independent) and the *2-of-N gate* (an **additional** requirement, layered on top, triggered **only by a price increase**). So the three outcomes are: within tolerance → auto-confirmed (5.4, no approval); routed with **no price increase** (decrease / quantity / structural / currency) → resolvable by a **single approver**; routed **with a price increase** → needs **N distinct pool sign-offs**.

**"Is this a price increase?" — any-line rule (confirmed).** The 2-of-N gate applies iff **any** matched line is a price increase beyond tolerance — a positive 5.3 variance whose magnitude ≥ the tolerance 5.4 recorded (`Reconciliation.toleranceApplied`). A mixed PI (one line up, one down) **does** hit the gate: a PI containing a price rise shouldn't escape it because another line fell. Computed live in `PiApprovalService#requiresSignOff` from the stored lines — no new flag.

| Method | Path | Role | Notes |
|---|---|---|---|
| `POST` | `/api/proforma-invoices/{piId}/approvals` | APPROVER | Record a sign-off (`{ comment? }`). Confirms on the single approval (non-increase) or the Nth distinct pool sign-off (increase). |
| `POST` | `/api/proforma-invoices/{piId}/rejections` | APPROVER | Record a rejection (`{ reason }`, required). One rejection rejects the PI. |
| `GET` | `/api/proforma-invoices/{piId}/approval-state` | Any authenticated | Progress: `requiresSignOff`, `requiredApprovals`, `approvalsCollected`/`Remaining`, `signedOffUserIds` (the ticked checkboxes), `thresholdMet`, `approvable`/`blockedReason`, `eligiblePoolSize`, and the immutable `actions` list. |

**Eligibility, by path.** The `APPROVER` role is enforced at the endpoint (`@PreAuthorize` → a non-approver gets `FORBIDDEN`), which *is* the whole eligibility rule for the single-approver path — pool membership is **not** required for a decrease. The **2-of-N** path adds, in the service: the approver must be a currently-eligible **pool member** (`NOT_IN_APPROVER_POOL` otherwise — this is exactly why 5.6 modelled the pool separately from the role), and the required sign-offs must come from **distinct** approvers (`ALREADY_SIGNED_OFF` if the same person signs twice — distinctness is the entire point of multi-party approval).

**Self-approval forbidden (both paths).** The PO creator (`PurchaseOrderService#getCreatedBy`, a new `@NamedInterface` surface) and the PI logger cannot approve — a segregation-of-duties control fitting the compliance positioning (`SELF_APPROVAL_FORBIDDEN`). Rejection is *not* self-restricted: flagging a problem with your own order is fine. *(Flagged for the Product Owners: a very small company where the PO-raiser is one of only two approvers could deadlock — relax self-approval only if they ask; defaulting to prevent it.)*

**Confirming / rejecting.** On reaching the threshold the PI transitions to `APPROVED`; a single rejection → `REJECTED` (one "no" stops it — the gate guards against approving a rise too easily, not against rejecting one). Every action is an immutable `ApprovalAction` row (who / when / the `reconciliation_id` they acted on = "the variance they saw" / comment) — same construct-only audit shape as `PurchaseOrderSend`. Only a `ROUTED_FOR_APPROVAL` PI can be acted on (`PI_NOT_AWAITING_APPROVAL` otherwise).

**The stranded-pool guard (from 5.6).** If the active pool can no longer reach the required count (members deactivated since routing), `approval-state` returns `approvable: false` with a `blockedReason`, rather than the PI waiting forever. The threshold is **never auto-lowered** (that would defeat the control) — an ADMIN fixes the pool. Reachability is `|distinct sign-offs already banked ∪ currently-eligible pool|`; banked sign-offs still count even if that member later left the pool.

**Notification (stubbed).** When a PI is routed, the eligible pool approvers are notified through the shared `EmailSender` seam (4.7) — the **third** consumer (invites, PO send, approval requests), console-logged until Notifications lands. Best-effort: a notification failure never fails the PI log. (Stub simplification: a role-only approver who isn't in the pool can act on a decrease but isn't separately emailed — noted for the real Notifications work.)

### Status lifecycle & audit (Story 5.7)

The final story: makes the lifecycle a single explicit state machine, and the audit trail a genuinely immutable, consolidated record — the substrate the roadmap's compliance/director-liability positioning leans on. Threads 5.3/5.4/5.5's states and records together rather than leaving them implicit across four stories.

**The status lifecycle, in one place.** `ProformaInvoiceStatus.canTransitionTo` defines the permitted transitions; every status change goes through one guarded `ProformaInvoice#transitionTo`, so an illegal move (`AUTO_CONFIRMED` → `ROUTED_FOR_APPROVAL`, a `REJECTED` PI silently becoming `APPROVED`) throws `INVALID_STATUS_TRANSITION` instead of corrupting state. States: `LOGGED` → `AUTO_CONFIRMED` (5.4) | `ROUTED_FOR_APPROVAL` (5.4) → `APPROVED` | `REJECTED` (5.5); any non-terminal state → `SUPERSEDED`. A same-state move is an idempotent no-op (so a deterministic re-evaluation can't trip the guard). This is defense-in-depth — the service preconditions (`PI_NOT_AWAITING_APPROVAL` etc.) already prevent reaching an illegal transition through the API; the guard ensures no code path can.

> **Naming note:** the story calls the routed state `PENDING_APPROVAL`; the built value is **`ROUTED_FOR_APPROVAL`** — the name 5.4/5.5 already persist, the frontend binds to, and the cross-repo money/tolerance fixture references. Deliberately not renamed (a live enum value across the DB, tests, and two repos) — the lifecycle coherence 5.7 adds comes from the guarded transitions, not the label. They are the same state.

**`SUPERSEDED` is a real state (5.1 cardinality).** A corrected re-issue now moves the prior PI to `SUPERSEDED` (`ProformaInvoice#supersede`, still also `active=false`) rather than leaving it in a misleadingly active-looking status.

**The audit trail — genuinely append-only, enforced structurally.** A dedicated `reconciliation_audit_events` log (Flyway V36) records the chronology per PI: `PI_LOGGED` (5.2), `COMPARISON_RECORDED` (5.3), `AUTO_CONFIRMED`/`ROUTED_FOR_APPROVAL` (5.4), `APPROVAL_RECORDED`/`APPROVED`/`REJECTED` (5.5), `SUPERSEDED`. Immutability is **enforced at the model/repository level, not by convention**: `ReconciliationAuditEvent` is construct-only (no setters/mutators), and `ReconciliationAuditEventRepository` extends the bare Spring Data `Repository` marker exposing **only** `save` (append) and `findAll` — no `delete`/`deleteById`/`deleteAll` anywhere, so there is no application code path to alter or remove an entry (asserted by a reflection test). Written in the same transaction as the state change it records, so the trail never drifts out of step with what happened.

**Tolerance in force at the time — recorded, so history stays explicable.** The evaluation records the tolerance value that applied when it ran (`Reconciliation.toleranceApplied`, since 5.4; the audit event's detail restates it), *not just* the outcome. After a company later changes its tolerance setting, a historical auto-confirm is still explicable ("passed because tolerance was 2% then") — verified by a test that changes the setting and confirms the old value survives on the record.

**Variance-trend context.** Variance is stored on **every** reconciliation including auto-confirmed ones (5.3/5.4); 5.7 adds `supplier_id` to the reconciliation record (Flyway V37) so a per-supplier drift-trend query has supplier + date (`created_at`) + variance (on the lines) + direction (the variance sign) without joining through the PO. The trend *reporting* itself is out of scope (dashboard territory) — this just ensures the data supports it.

**Consolidated retrieval** (`ReconciliationQueryService`, all reads open to any authenticated company user):

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/proforma-invoices/{piId}/reconciliation-detail` | Everything Screen 4 needs in one call: the PI (status, lines), the comparison (legs, per-line variance, outcome, tolerance in force), the approval progress, and the audit trail — composed from the existing per-concern responses, each still the source of truth for its own data. |
| `GET` | `/api/purchase-orders/{poId}/reconciliations` | Every reconciliation logged against a PO, including superseded PIs (one summary per PI, its latest comparison), newest first. |
| `GET` | `/api/reconciliation/pending-approval` | The approver's queue — reconciliations whose PI is currently `ROUTED_FOR_APPROVAL`. (The Feature 7 dashboard will want the same data.) |

The audit trail is readable by company users but **mutable by no one, including ADMIN** — there is no write path to it beyond the append the lifecycle itself performs.

### Still to come (forward notes, not yet built)

**Feature 5 is complete** — the reconciliation engine: log a PI, compare it three ways, evaluate against a configurable tolerance with the exclusive boundary, route or auto-confirm, collect single or 2-of-N approvals with the increase/decrease asymmetry, and keep an immutable audit trail of all of it. The notes below are already-confirmed decisions for *later* features; nothing in Feature 5 is outstanding beyond Product Owner confirmation the two defaults (unit-price basis, 2% tolerance) are the right values.

> **Feature 6 scope — settled:** Roadmap v2 had bundled the three-way match **plus** freight invoice reconciliation **plus** NCR true-cost/dispute letters under one number (~1 feature vs 3). **Decision: Feature 6 = the payment schedule & three-way match only** (see the Payments section below); freight reconciliation and NCR become their own later features. Rationale: it's the core financial control and the biggest reuse of Feature 5's engine, and it lays the goods-receipt foundation the other two need, so nothing is wasted by sequencing them after.

**Multi-currency PI — reject-and-route, built end to end.** When a supplier's PI arrives in a different currency than its PO, Feature 5 does **not** treat this as a blunt `400`/`VALIDATION_ERROR`, and **does not auto-convert** (no FX; Phase 2's FX-rate machinery is separate): 5.3 flags the mismatch on the reconciliation record, 5.4 routes on it (`CURRENCY_MISMATCH`), and 5.5's single-approver path resolves the routed PI (a currency mismatch alone, with no price increase, doesn't invoke the 2-of-N gate). Left here as the pointer to where Phase 2's FX work will eventually plug in.

---

## Payments & three-way match (Feature 6)

**Owner:** Story 6.1 (payment schedule data model), 6.2 (due-date calculation), 6.3 (the payment queue), 6.4 (invoice logging), 6.7 (discrepancy / credit ledger). Still to come this feature — **all blocked on Feature 7's GRN**: the three-way match + blocking (6.5), mismatch routing (6.6), and the release flow (6.8). SHVOY **schedules and gates** payments — Pay/Hold is a status decision; it never moves money (actual settlement is outside the product, per the wireframes).

### Payment data model (Story 6.1)

- `Payment` — tenant-scoped, one `purchase_order_id` (the PO the obligation arises from), a `type` (`DEPOSIT`/`BALANCE`, string enum), a **snapshotted** amount (`amount_amount` 2dp + `currency`), a **nullable** `due_date` (`LocalDate`), and a `status` (string enum: `PENDING`/`BLOCKED`/`READY_TO_PAY`/`PAID`/`ON_HOLD`). Flyway V38.
- **Amount is a snapshot**, copied from the 4.3 deposit/balance split at creation, never recomputed from the PO on read — same principle as PO line prices. The invariant "a PO's payment amounts sum to its order total exactly" holds by construction (the split guarantees `deposit + balance == orderTotal`).
- **Due date is deliberately nullable.** A balance's due date may be unknowable until its anchor event (BL date, arrival) occurs in Feature 7; a payment exists with a fixed amount but a pending due date from day one. *Calculating* due dates is 6.2; the status *transitions* are defined but enforced in 6.8.
- **No supplier-terms reference is stored on the payment** — it holds amounts and dates, not terms. Which terms drive the due-date calculation (including the still-open dual-term-supplier question) stays isolated in 6.2, the same discipline as the tolerance and carton rules.

### When payments are created — the first cross-module domain event

Payment records are created **at PO generation** (4.6), the moment the deposit/balance amounts are definitively locked — via the codebase's **first real cross-module domain event**, `PurchaseOrderGeneratedEvent` (`@NamedInterface("po-events")`). `purchaseorders` publishes it (`ApplicationEventPublisher`) and knows nothing about who listens — no dependency on `payments`; the `payments` module reacts (`PaymentScheduleService`) and creates the obligations. This establishes the pattern Feature 7 (documents → anchor dates → due-date recalculation) and Notifications will reuse.

- **Synchronous `@EventListener`, deliberately** — runs inline on the publisher's thread inside the generation transaction, so payment creation commits **atomically** with generation and, critically, the tenant is preserved (`TenantContext` is a `ThreadLocal`; an `@Async`/`@ApplicationModuleListener` on another thread would lose it and the `@TenantId` insert would fail).
- A **fat event** carrying the financial figures (`orderTotal`, and the `deposit`/`balance` split — both null when the supplier has no payment terms) so the listener never calls back into `purchaseorders` to read them.
- **What gets created:** deposit % > 0 → a `DEPOSIT` + a `BALANCE` payment; **0% deposit → a single `BALANCE` for the full amount** (a confirmed, supported rule, tested explicitly) — which also covers a PO with no payment terms configured (no split at all).
- **Abandoned/never-sent POs:** payments simply track the PO for MVP; if a cancellation flow arrives later it can cascade — noted, not built.

### Due-date calculation & the anchor-date recalculation seam (Story 6.2)

The rule: **balance due date = the anchor event's date + the terms' signed days offset**; **deposit due date = the PO generation date** (the MVP default — deposits are effectively "due now" to unlock production, not anchored to a shipment event). The real deliverable is the *seam*, because anchor dates mostly don't exist yet when the payment is created.

- **The offset is signed.** Roadmap v2's "anchor date ± days" is real — "due N days *before* arrival" is a legitimate term — so 3.3's `daysOffset` validation was relaxed from `@PositiveOrZero` to `@Min(-365)/@Max(365)`. The domain column was already a signed `int`.
- **Deposit due date is set at creation** (the generation date, carried on `PurchaseOrderGeneratedEvent` — which grew `supplierId` + `generationDate` in 6.2). Balance payments are born **without** a due date, holding the anchor terms snapshotted onto them (`anchor_event` + `days_offset`, Flyway V39) so the due date can be filled in later.
- **The anchor-date seam** — `PaymentDueDateService#applyAnchorEventDate(poId, anchorEvent, anchorDate)`, invoked via the 6.1 event pattern: `AnchorEventDateKnownEvent` (`@NamedInterface("payment-events")`, defined on the *consuming* payments side as its inbound contract) is what **Feature 7 will publish** when it logs a BL / invoice / ex-factory / arrival date. Until then, balance due dates stay null (`PENDING`, no fake date — 6.3's queue must show "awaiting shipment date" honestly). Only balance payments whose *snapshotted* anchor event matches the incoming one are touched — a BL date doesn't move an arrival-anchored payment.
- **Re-entrant by design.** A revised anchor date (a confirmed ETA shifts, an arrival corrects) recalculates the due date and audits the change (old → new, why). A date resolving to the same due date is a deterministic no-op. This is the foundation of Phase 2's "recalculate as ETA shifts".
- **Every due date records its derivation** — the anchor date applied is kept on the payment (`anchor_date_applied`), and each set/recalculation is an immutable `payment_audit_events` entry (Flyway V40) — same genuinely-append-only shape as the reconciliation audit trail (construct-only entity; repository with no delete/update path).
- **Effective-terms resolver, isolated.** "Which terms govern this PO's payments?" is answered in one place (`EffectivePaymentTermsResolver`), today returning the supplier's single 3.3 term set. When the **dual-term supplier** model lands, only this resolver changes — same discipline as the tolerance/carton/variance-basis rules.

> **Two flagged assumptions for the Product Owners (each isolated so the answer is a contained change):**
> 1. **Deposit timing = due at generation.** If deposits carry their own terms (e.g. "due 7 days after PO"), the model extends — the assumption lives in one place (`Payment.deposit`).
> 2. **Terms snapshotted at PO generation.** An order's payment terms are fixed when it's placed (consistent with how prices snapshot; commercially, you don't retroactively re-term a placed order). This substantially defuses the dual-term question: in-flight POs are unaffected by a supplier's term transition, so the remaining dual-term question shrinks to only *"which terms does a **new** PO snapshot?"* — a much smaller decision. Worth explicit confirmation, but snapshot-at-generation is the defensible default. (Verified by a test: changing a supplier's terms after generation does not move an existing PO's due dates.)

### The payment queue (Story 6.3)

The dashboard-facing "what do we owe, to whom, and when." **Read-only** — no state, no mutation (Pay/Hold is 6.8; the match statuses `BLOCKED`/`READY_TO_PAY` are *displayed* here once 6.5 computes them, never computed here). All reads open to any authenticated company user (the dashboard is everyone's landing page); tenant-scoped.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/payments` | The queue — each row joined across PO and supplier (PO reference, supplier name, type, amount, due date, status) in one call. **Defaults to the operational view: unpaid, sorted by due date ascending, undated grouped after.** Filters: `status`, `type`, `dueFrom`/`dueTo` (a due-date range, inclusive, excludes undated), `overdue=true`. Paged (`page`/`size`, size capped at 200); the response carries `totalCount` for the pager. |
| `GET` | `/api/payments/stats` | The two payment-derived dashboard aggregates (`overdueCount`, `dueWithin5DaysCount`) without fetching the full queue. |

**Two derived boundaries — pinned, because they render as red flags and counts a finance user acts on (and the frontend implements them too):**

- **Overdue = due date is *strictly before* today, and not `PAID`.** Derived at read time, never a stored flag (same principle as a price's in-date/expired status). **Due *today* is *not* overdue.** Worked example, today = 2026-08-15: due `2026-08-14` → overdue; due `2026-08-15` → **not** overdue; due `2026-08-16` → not overdue.
- **Due within 5 days = due date in `[today, today+5]`, *inclusive* of day 5, not `PAID`.** Worked example, today = 2026-08-15: `2026-08-15` (today), `2026-08-18`, and `2026-08-20` (today+5) all count; `2026-08-21` (today+6) does **not**; `2026-08-14` (overdue) does **not**.

**Undated payments shown honestly.** A balance whose anchor event hasn't occurred (6.2) has a null due date; it appears in the queue with `awaitingDueDate: true` and `dueDate: null` — never hidden, never given a fake date — and sorts *after* all dated payments (its clock hasn't started). The `overdue`/`awaitingDueDate` flags are per-row; a null-due-date payment is never overdue.

### Invoice logging (Story 6.4)

The fourth leg of the three-way match — the supplier's final invoice (PO = PI = GRN vs **invoice**). Same "record faithfully, judge later" posture as PI logging (5.2): capture what the supplier sent; the match (6.5) judges it. In the `payments` module.

| Method | Path | Role | Notes |
|---|---|---|---|
| `POST` | `/api/purchase-orders/{poId}/invoices` | ADMIN/PURCHASING/FINANCE | Record an invoice (`{ invoiceReference, amount, currency, invoiceDate, claimedCreditAmount?, claimedCreditReference? }`). `PO_NOT_READY_FOR_INVOICE`/409 unless the PO is `GENERATED`/`SENT`. A second invoice supersedes the prior active one. |
| `GET` | `/api/purchase-orders/{poId}/invoices` | Any authenticated | Every invoice logged against a PO — active + superseded — newest first. |
| `GET` | `/api/invoices/{id}` | Any authenticated | One invoice. |

- **Header-level (MVP), flagged.** Reference, total amount (2dp), date, and an optional claimed credit — **no line items**. Screen 6 shows a single "Invoice $xx" column, and line-level price scrutiny already happened at PI reconciliation (Feature 5). Modelled so lines *could* be added later (the AI extraction layer), not built speculatively. *Flagged to the Product Owners: if they need invoice-vs-PI comparison at line level (a re-priced line inside a correct-looking total), this changes — but the PI gate already did line-level price control.*
- **Record faithfully.** Validation is well-formedness only (non-blank reference, positive 2dp amount, real ISO 4217 currency, a date). Amount/currency **disagreement with the PO/PI is recorded, not rejected**; a differing currency is recorded with **no FX conversion** (consistent with 5.3) — the match flags it.
- **Claimed credit** is what the supplier *says* they deducted, captured as-stated. Validating it against an open ledger credit is 6.7's rule, applied at match time (6.5) — not at entry.
- **One active invoice per PO** (MVP): a correction **supersedes** the prior (kept for audit, `SUPERSEDED`), same as a PI. *Flagged to the Product Owners: the consequential cardinality question — do suppliers ever invoice a PO in **parts** (deposit invoice + balance invoice, or per shipment)? If yes this becomes invoice-per-payment, a meaningful change better known now.*
- **One internal operation** (`InvoiceService#log`) for manual entry now and the AI feed later, same as 5.2.
- **First real caller of the 6.2 anchor seam.** On logging, `InvoiceService#log` publishes an `AnchorEventDateKnownEvent` for the `INVOICE` anchor with the invoice's date (best-effort — a trigger failure never fails the logging). `PaymentDueDateService` (6.2) reacts and sets the due date of any balance payment *anchored to the invoice* (payments anchored to BL/arrival/ex-factory are untouched — they wait on Feature 7; the event is published unconditionally and the seam filters). A superseding invoice with a different date re-fires it; 6.2's re-entrancy recalculates and audits. A nice validation that the option-2 sequencing works — the seam built ahead of Feature 7 is exercised by 6.4 first.

### Discrepancy / credit ledger (Story 6.7)

The open-credit ledger — the durable memory of shortfalls, damage, and agreed deductions — and the rule it exists to enforce: **an invoice claiming a prior credit is only accepted as correct if it matches an OPEN entry here.** Stops agreed deductions being forgotten, and stops suppliers claiming deductions that were never agreed. In `payments`; three future writers (6.5 apply, 6.6 mismatch resolution, the NCR flow) go through its one service.

- `CreditLedgerEntry` — tenant-scoped, one `purchase_order_id`, a 2dp `amount` + currency, a `cause` (`SHORT_SHIPMENT`/`DAMAGE`/`AGREED_PRICE_CORRECTION`/`QUALITY_FAILURE`/`OTHER`, detail required for `OTHER`), a nullable `target_invoice_id` (the future invoice it applies against — null while `OPEN`, set when applied), a `status` (`OPEN`/`APPLIED`/`CANCELLED`), and `logged_by`. Flyway V42.

| Method | Path | Role | Notes |
|---|---|---|---|
| `POST` | `/api/credit-ledger` | ADMIN/PURCHASING/FINANCE | Log a credit (`{ purchaseOrderId, amount, currency, cause, causeDetail?, ncrReference?, targetInvoiceId? }`). |
| `GET` | `/api/credit-ledger` | Any authenticated | List; **defaults to OPEN entries**; `status`/`purchaseOrderId` filters. |
| `GET` | `/api/credit-ledger/stats` | Any authenticated | `{ openCount }` — the **"Open discrepancies: N"** dashboard stat, the third Screen-1 stat 6.3 left stubbed. |
| `GET` | `/api/credit-ledger/{id}` | Any authenticated | One entry. |
| `POST` | `/api/credit-ledger/{id}/cancel` | ADMIN/FINANCE | Close with a required, audited reason (`OPEN → CANCELLED`). |

- **The matching rule, pinned.** `CreditLedgerService#checkClaim(poId, claimedAmount)` — the reusable operation 6.5 calls — answers matched/not with a reason: a match is the **same PO, `OPEN` status, and the exact 2dp amount + currency** (credits are agreed figures, so there is **no tolerance**). Outcomes: `MATCHED` / `AMOUNT_MISMATCH` (an OPEN entry exists for the PO but no exact amount) / `NO_OPEN_CREDIT` (wrong PO, none logged, or only `APPLIED`/`CANCELLED` ones). It only **answers** — applying is the separate `apply(id, invoiceId)`, so a check never mutates the ledger. *(Whether a near-miss amount should route to a human vs hard-fail is a 6.5 behaviour question — this operation just answers.)*
- **Applies exactly once.** `OPEN → APPLIED` records the consuming invoice; a second application throws `CREDIT_NOT_OPEN` (enforced on the entity), and an `APPLIED` entry never matches again.
- **Append-only, immutable.** Amount and cause have no setters and there is **no update endpoint** — a correction is cancel-and-relog (an editable entry nudged to match a claim would defeat the control). Every transition (logged / applied / cancelled) is an immutable `credit_ledger_audit_events` entry (V43) — same construct-only, no-delete-path shape as the reconciliation (5.7) and payment (6.2) trails. Verified by a structural reflection test.
- **Seams, not built:** `QUALITY_FAILURE` + a nullable `ncr_reference` exist for Roadmap v2's NCR-caused credits, pending the phasing decision — no NCR functionality here.

> **One flagged Product Owner question:** MVP is **one-claim-one-entry, whole-entry** application. Can a supplier's invoice legitimately claim a deduction spanning **several** open credits at once (e.g. two short-shipments netted against one balance invoice)? If yes, `checkClaim` grows a summing rule — better known before 6.5 is built. (Partial application of a *single* entry is explicitly deferred — that's where complexity explodes.)

---

## Phase 2 — Multi-currency FX (forward notes, not yet built)

**Status:** nothing in this section is built or scheduled — Phase 2, not MVP. MVP is single-currency USD (see Money above). Recorded here verbatim from the Product Owner's answer (Consolidation ticket) so the detail isn't lost before Phase 2 is actually scoped.

The Product Owner's description of the eventual multi-currency FX logic:

- **Rate source:** HMRC's monthly customs exchange rate (not a live/spot rate).
- **Publication timing:** HMRC publishes the rate for a given calendar month on the **penultimate Thursday** of the *preceding* month.
- **Lookup rule:** a PI's date maps to a calendar month, and that month's published HMRC rate is the one used — a PI-date-to-calendar-month lookup, not a PI-date-to-nearest-rate lookup.
- **Caching:** HMRC's rates are consumed as a CSV/XML cache (fetched and stored locally), not queried live per transaction.
- **Contract-fixed-rate override:** a per-supplier/per-PO toggle to use a rate fixed in the supplier contract instead of the HMRC monthly rate — an override, not a replacement of the default mechanism.
- **Audit trail:** the rate actually used is stored **per transaction** — not just the current/latest rate — so a historical transaction's conversion can always be reproduced/audited later, the same "snapshot, not live reference" principle already used for PO line pricing (see Purchase orders above).

**Flagged as having eventual model implications, not just a computation detail:**
- **"Store the rate used per transaction"** implies a real schema field (or table) once Phase 2 is scoped — not a derived/computed value, since the whole point is reproducing what was actually used historically even if HMRC's published rate for that month is later revised or the cache is rebuilt.
- **The contract-fixed-rate toggle** is supplier/PO configuration that overrides a default, with the two co-existing — the same underlying shape as the roadmap's **dual-term suppliers** (current vs. target payment terms) and **future-dated pricing**. These three are plausibly "the same kind of thing" (supplier config that overrides a default, with a transition/override period) and worth modelling together once a real case lands, rather than each being bolted on separately. Deliberately not modelled yet — held pending the future-dated/dual-term Product Owner answer, per the Consolidation ticket's scope notes, so this gets designed once, correctly, rather than piecemeal.

---

## Dates and timestamps

**Owner:** Cleanup Story 5 (date field mapping & container-fill deadline timezone). Field-level mapping is now **settled** — Story 3.4 gave the rule its first concrete case (SKU price validity dates), so this is no longer a rule with nothing to point at. Only the container-fill deadline timezone remains open, parked until Feature 8.

Rule: business dates are `LocalDate` (serialises as `yyyy-MM-dd`), real points in time are `Instant` (serialises as ISO-8601 UTC).

| Field | Type | Status |
|---|---|---|
| SKU price validity dates (`valid_from`/`valid_to`) | `LocalDate` | Implemented — Story 3.4, `SkuPrice` |
| Requested ETD | `LocalDate` | Implemented — Story 4.4, `PurchaseOrder#requestedEtd` |
| Confirmed ETD | `LocalDate` | Not yet implemented (Feature 4) |
| Payment anchor date (the real date an order's BL/invoice/arrival occurred, as opposed to `PaymentTerms.anchorEvent`, which only names *which kind* of event) | `LocalDate` | Not yet implemented — lands against a real order, Feature 7 |
| All `created_at`/`updated_at` timestamps | `Instant` (UTC) | Implemented throughout |
| Container-fill decision deadline | `Instant` (UTC) — **timezone for display/evaluation not yet decided**, see below | Not yet implemented (Feature 8) |

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
- Feature 3, Story 3.3: added Payment terms section — `PaymentTerms` entity/endpoints, the deposit/balance split rule (HALF_EVEN, remainder on balance), added `Money.minus`.
- Feature 3, Story 3.4: added SKU & price model section — `Sku`/`SkuPrice` entities (validity-windowed price history, not a single current price), derived in-date/expired status, added `UnitPrice` (4dp sibling to `Money`, renamed the shared serializer/deserializer from `MoneyAmountSerializer`/`Deserializer` to `AmountSerializer`/`Deserializer` accordingly). Settled the Dates and timestamps field-level mapping (Cleanup Story 5), except the still-open container-fill deadline timezone.
- Feature 3, Story 3.5: added SKU & price entry/upload section — manual entry + bulk CSV upload endpoints, the write-time supersession rule (auto-close vs. `AMBIGUOUS_PRICE_WINDOW`), `DUPLICATE_SKU` (now enforced), added `ValidationException` for hand-raised `VALIDATION_ERROR`s. Two MVP defaults flagged for PO confirmation: the canonical CSV template, and future-dated prices being out of scope. Also documented a latent `IllegalArgumentException`-vs-`VALIDATION_ERROR` gap in `Money`/`UnitPrice` construction from user input, closed for this story's endpoints.
- Feature 3, Story 3.6: added Discount tiers section — `DiscountTier` entity attached to `SkuPrice` (not `Sku`, so tiers stay versioned with the price they modify), full-replace `PUT`/`GET` endpoints. Two MVP defaults flagged for PO confirmation: absolute unit price per tier (not a percentage discount), and monotonically non-increasing price as quantity rises (enforced against the base `SkuPrice` too, not just tier-to-tier). Tier columns in the 3.5 price-file upload remain unimplemented — no confirmed real format to build against yet.
- Feature 3, Story 3.7: added Carton/pack size section — nullable `carton_size` on `Sku` (not `SkuPrice` — the opposite attachment point from discount tiers, since it's a packing property, not a price property), folded into the existing 3.5 SKU update endpoint. Added `Sku#isCartonMultiple`/`#nearestCartonMultiple` as the reusable rule for Feature 4. Flagged the literal "nearest" rounding behavior (can round below the requested quantity) for a Product Owner sanity check before Feature 4 relies on it.
- Feature 3, Story 3.8 (final story of the feature): added the Price resolution section — `PriceResolutionService`, `@NamedInterface("price-resolution")` alongside its result type as the explicit Feature 3 → Feature 4/5 contract. Resolves price (via `SkuPrice#isInDate`), tier (highest threshold ≤ quantity), and carton validity (via `Sku`'s 3.7 rule) as of a required, non-defaulted date; `priceFound: false` is the stable no-valid-price signal, never an exception or a silent fallback. Defensive overlapping-window handling resolves to the latest `validFrom` and logs a data-integrity warning. Read-only, deterministic, no new schema. Feature 3 is now complete.
- Feature 4, Story 4.1 (first story of the feature): added the Purchase orders section — `PurchaseOrder`/`PurchaseOrderLine` entities, model only (no endpoints yet). Established the price-snapshot principle (a line stores the price it was created with, never a live `SkuPrice` reference) and per-company sequential PO numbering (`PoNumberGenerator`, `po_number_counters`), lock-based and race-safe — the concurrency approach went through two iterations before landing on one that's safe under both Postgres's poisoned-transaction behavior and real connection-pool limits (see that section for what didn't work and why).
- Feature 4, Story 4.2: added the Line pricing subsection — `PurchaseOrderLinePricingService` wires a line to 3.8 as of the current/draft date, snapshotting price/tier/carton/validity in one shot via `PurchaseOrderLine#applyPriceResolution`. Added `CURRENCY_MISMATCH`. Two things it inherits rather than reimplements: the pending carton-rounding-rule PO question (via 3.7's shared rule) and SKU/supplier ownership enforcement (via `PriceResolutionService`'s own check). One new decision it introduces, flagged for PO confirmation: single currency per PO.
- Feature 4, Story 4.3: added the Totals & money composition subsection — the Money contract's rules run for real for the first time. Added `UnitPrice#multiply` (line total: round the raw 4dp x quantity product once, HALF_EVEN) and `PurchaseOrderTotalsService` (order total: sum of already-rounded line totals, never a rounded sum of unrounded values; deposit/balance split via `PaymentTerms#split`, amounts only — due dates stay Feature 7's). Wired into `PurchaseOrderLinePricingService` so totals never go stale after a line is (re)priced. Tests deliberately engineer fixtures where sum-of-rounded diverges from round-of-sum, and where HALF_EVEN diverges from HALF_UP, rather than relying on incidental numbers.
- Feature 4, Story 4.4: added the Creation & draft management subsection — the first real PO endpoints (create/list/get, add/edit/remove line, set ETD, cancel), all wiring together 4.1/4.2/4.3 rather than introducing new domain logic. Added `PurchaseOrderStatus.CANCELLED` (draft-only soft-delete), `PO_NOT_EDITABLE` (the DRAFT-only mutation guard, centralised in `PurchaseOrderService#assertEditable`), and `CurrentUserContext` (mirrors `TenantContext` for "current user," first needed for `createdBy`; resolved the same way, including a new `X-Debug-User-Id` header in `local`/`test` with, deliberately, no fallback default). PO number assignment stays at creation time (pilot-scale default: abandoned drafts leave sequence gaps, an accepted cost) and a past requested ETD is rejected — both confirmed rather than left open. Settled the Dates and timestamps table's Requested ETD row.
- Consolidation ticket (Roadmap v2 + PO answers, not tied to a single story): applied a batch of settled corrections before they could be forgotten. Added `AnchorEvent.EX_FACTORY` (Roadmap v2's fourth anchor option). Capped `PaymentTermsRequest#depositPercentage` at 1 decimal place via `@Digits(integer = 3, fraction = 1)` (33.5 valid, 33.55 rejected). Settled the Money section's Currency scope question — MVP is single-currency USD — and corrected every USD-assumed example/fixture across this doc and the test suite accordingly (previously an arbitrary mix, mostly GBP). Added the Feature 5 and Phase 2 sections to record decisions already confirmed by the Product Owner ahead of either feature actually being built: Feature 5's multi-currency-PI reject-and-route behaviour (not a hard validation error, and does not auto-convert) and its variance-stored-on-every-match requirement; Phase 2's HMRC monthly-rate FX logic in full, flagging the per-transaction rate storage and the contract-fixed-rate toggle as having eventual model implications (the toggle is plausibly the same underlying "supplier config overriding a default" shape as dual-term suppliers and future-dated pricing — deliberately held, to be modelled together once, not piecemeal). Dual-term suppliers and the freight/NCR Phase-1 scoping decision are explicitly out of scope here — genuine model/scoping decisions, not quick corrections.
- Feature 4, Story 4.5: added the Expired-price handling & override subsection — `PurchaseOrderFinalisationGateService`, the gate 4.6 (generation) must call before finalising a draft PO. Re-resolves every line as of today (never trusting the already-snapshotted flags) and blocks (`PO_HAS_EXPIRED_PRICES`) unless a complete override (non-blank reason + a manual price for every blocked line) is supplied, in which case it persists an immutable audit record — `PurchaseOrderPriceOverride`/`PurchaseOrderPriceOverrideLine`, construct-only, no mutators, matching Roadmap v2's audit-trail standard. Added `PriceResolutionResult#everPriced` (distinguishes expired from never-priced when `priceFound` is `false`). Built against option (a) of the still-open override-price question (require a manual price) — flagged, not resolved, pending the PO. No controller in this story, same as 4.2/4.3: role enforcement (`PURCHASING`/`ADMIN`) and the currency-consistency check on a manual override price are both explicitly deferred to 4.6's future finalise endpoint, not forgotten.
- Feature 4, Story 4.6 (sixth story of the feature): added the Generation & document subsection — the first PDF this codebase produces. `POST .../generate` runs 4.4's status guard, this story's own two new preconditions (≥1 line, ETD set — `PO_NOT_READY_TO_GENERATE`), then 4.5's expired-price gate; on success, every line is re-resolved and re-snapshotted as of the generation date (substituting an override's manual price where 3.8 still can't resolve one), totals recomputed once, a PDF rendered and stored in S3, and the PO transitions `DRAFT`→`GENERATED` with `generated_by`/`generated_at` recorded. `GET .../document` retrieves the stored PDF. PDF approach researched and chosen deliberately: Thymeleaf (data/template separation) piped into openhtmltopdf-pdfbox (LGPL 2.1+, actively maintained), explicitly rejecting iText (AGPL/commercial — the license trap the story flagged) and JasperReports (also GPL/commercial) up front. Added two narrow cross-module surfaces the document needed (`SupplierService#getSummary`, `SkuService#getSummary` — both new `SkuSummary`/`SupplierSummary` types, not the full API response shapes). Acknowledged, not built: currency-consistency isn't re-checked during generation-time re-resolution (flagged as an accepted gap, not an oversight). Feature 4 has one story left: 4.7 (send to supplier).
- Feature 4, Story 4.7 (seventh and final story of the feature): added the Send to supplier subsection and the Email delivery section. `POST .../send` requires `GENERATED`/`SENT` status (`PO_NOT_SENDABLE` otherwise) and a supplier contact email (`SUPPLIER_MISSING_CONTACT_EMAIL` otherwise); sends the already-generated PDF via the new `EmailSender` abstraction and transitions `GENERATED`→`SENT` on first send. **Resend confirmed allowed** (not blocked) — appends a new immutable `PurchaseOrderSend` audit row (who/when/recipient/document snapshot) every time, never re-prices or regenerates. Extracted `EmailSender`/`EmailMessage`/`EmailAttachment`/`ConsoleEmailSender` from what used to be `InvitationService`'s (2.3) own inline `log.info` — both flows now share one seam, ready for a single SES swap later; `RegistrationService`'s equivalent verification-email log line was deliberately left untouched (out of scope, a natural third consumer later). Feature 4 is now **complete**: create → price/validate → total → draft-manage → expired-price gate → generate document → send.
- Feature 5, Story 5.1 (first story of the feature): renamed the Feature 5 section from "forward notes" to PI reconciliation and added `ProformaInvoice`/`ProformaInvoiceLine`, model only (no endpoints yet, that's 5.2). Two model-shape decisions made directly: cardinality is one-PO-to-many-PIs with an `active` flag identifying the current one (a supplier re-issuing a corrected PI is real enough to model for, not a hypothetical), and line correlation is by SKU rather than a direct PO-line FK (matches how suppliers actually reference products; messy cases deferred to 5.3). Currency lives on the PI, not inherited from the PO, so a mismatch is representable rather than unrepresentable.
- Feature 6, Story 6.7: added the Discrepancy / credit ledger subsection — the open-credit ledger + the rule it enforces ("an invoice claiming a credit is only accepted if it matches an OPEN entry"). `CreditLedgerEntry` (V42) + append-only `credit_ledger_audit_events` (V43); `POST/GET /api/credit-ledger`, `/stats` (the "Open discrepancies" dashboard stat that 6.3 stubbed), `/{id}`, `/{id}/cancel`. The reusable `CreditLedgerService#checkClaim` (6.5 will call it) answers matched/not: **same PO + OPEN + exact 2dp amount + currency, no tolerance** (`MATCHED`/`AMOUNT_MISMATCH`/`NO_OPEN_CREDIT`); it only answers, `apply` is separate. **Applies exactly once** (`OPEN → APPLIED`, `CREDIT_NOT_OPEN` on repeat); amount/cause **immutable** (no setters, no update endpoint — cancel-and-relog), all transitions immutably audited. `QUALITY_FAILURE` + nullable NCR reference are a seam (no NCR functionality). Flagged the multi-credit-claim question to POs. Logging PURCHASING/FINANCE/ADMIN; cancel FINANCE/ADMIN. `ModularityTests` green; full `mvn test` 394. **Option-2 exhausted — 6.5/6.6/6.8 all wait on Feature 7's GRN.**
- Feature 6, Story 6.4: added the Invoice logging subsection — the fourth leg of the three-way match, in `payments`. `POST/GET .../invoices` + `GET /api/invoices/{id}`, `PURCHASING`/`FINANCE`/`ADMIN` to log, `PO_NOT_READY_FOR_INVOICE` (new code) unless `GENERATED`/`SENT`. Same record-faithfully posture as 5.2: well-formedness only; amount/currency disagreement recorded not rejected (no FX). **Header-level (no lines)** and **one active invoice per PO** (supersession) — both flagged to POs (line-level invoices; partial invoicing, the consequential one). Claimed credit captured as-stated (validated at match time, 6.7's rule). **First real caller of 6.2's anchor seam**: logging publishes `AnchorEventDateKnownEvent` for the `INVOICE` anchor → an invoice-anchored balance's due date calculates; supersession with a new date re-triggers 6.2's re-entrant recalculation. New cross-module `PurchaseOrderService#assertOwnPurchaseOrderReadyForInvoice`. `ModularityTests` green; full `mvn test` 379.
- Feature 6, Story 6.3: added the Payment queue subsection — `GET /api/payments` (joined rows: PO ref, supplier name, type, amount, due date, status; defaults to unpaid + due-date-ascending + undated-last; filters by status/type/due-range/overdue; paged, size cap 200) and `GET /api/payments/stats` (overdue + due-within-5 counts). **Pinned the two derived boundaries with worked examples: overdue = strictly-before-today & not PAID (due-today is NOT overdue); due-within-5 = [today, today+5] inclusive.** Both derived at read time, never stored. Undated (awaiting-anchor) payments shown honestly (`awaitingDueDate: true`, null date, sorted last). Read-only, all company users. New cross-module surface `PurchaseOrderService#getSummary` → `PurchaseOrderSummary` (PO ref + supplier). `ModularityTests` green; full `mvn test` 370.
- Feature 6, Story 6.2: added the Due-date calculation & anchor-date recalculation seam subsection. Balance due = anchor date + **signed** offset (relaxed 3.3's `daysOffset` from `@PositiveOrZero` to `@Min(-365)/@Max(365)` — "due N days before arrival" is real); deposit due = PO generation date (set at creation). The anchor-date seam (`PaymentDueDateService#applyAnchorEventDate`, driven by `AnchorEventDateKnownEvent` that Feature 7 will publish) is **re-entrant** — a revised anchor date recalculates and audits old→new via an append-only `payment_audit_events` log (V40, same immutable shape as 5.7). Balance payments snapshot the anchor terms at generation (`anchor_event`/`days_offset`, V39); `PurchaseOrderGeneratedEvent` grew `supplierId`+`generationDate`; exposed `AnchorEvent` + `PaymentTermsService#getScheduleTerms` cross-module. Two flagged assumptions (deposit-due-at-generation, terms-snapshot-at-generation), each isolated; the effective-terms resolver (`EffectivePaymentTermsResolver`) is the single place the still-open dual-term question will change. `ModularityTests` green; full `mvn test` 363.
- **Feature 6 scope settled** (three-way match / payments only — freight reconciliation and NCR split into their own later features) and updated the note in Feature 5's "Still to come".
- Feature 6, Story 6.1 (first story): added the Payments & three-way match section — the `Payment` data model (`DEPOSIT`/`BALANCE`, snapshotted 2dp amount, nullable `LocalDate` due date, status enum `PENDING`/`BLOCKED`/`READY_TO_PAY`/`PAID`/`ON_HOLD`; Flyway V38). Payments are created **at PO generation** via the codebase's **first cross-module domain event** — `PurchaseOrderGeneratedEvent` (`@NamedInterface("po-events")`): `purchaseorders` publishes it knowing nothing about `payments`, and `PaymentScheduleService` reacts. Synchronous `@EventListener` on purpose (atomic with generation + preserves the `ThreadLocal` `TenantContext`, which `@Async`/`@ApplicationModuleListener` would lose). deposit>0 → deposit+balance; **0% deposit → balance only** (tested); amounts snapshotted from the 4.3 split, sum-to-total invariant asserted. Due dates modelled but uncalculated (6.2); status transitions defined but unenforced (6.8); no supplier-terms reference on the payment (isolated to 6.2). `ModularityTests` green (the new `payments → purchaseorders` event dependency stays within the named interface); full `mvn test` 353. Also added `payments` cleanup to the two PO-generating tests (generation now creates payment rows via the event).
- Feature 5, Story 5.7 (final story — **Feature 5 complete**): added the Status lifecycle & audit subsection. Made the lifecycle one guarded state machine — `ProformaInvoiceStatus.canTransitionTo` + a single `ProformaInvoice#transitionTo` reject illegal moves with `INVALID_STATUS_TRANSITION` (defense-in-depth; service preconditions already prevent reaching them via the API). Added `SUPERSEDED` as a real state (a corrected re-issue no longer leaves the prior PI misleadingly active). **Kept `ROUTED_FOR_APPROVAL` rather than renaming to the story's `PENDING_APPROVAL`** — same state, but the name is already persisted across DB/tests/frontend/the money-tolerance fixture; flagged as a deliberate call. Built a **genuinely append-only** audit trail (`reconciliation_audit_events`, Flyway V36): construct-only entity + a repository extending the bare `Repository` marker exposing only `save`/`findAll` — no delete path exists anywhere (asserted by a reflection test), threaded through 5.2–5.5 in the same transaction as each state change. Records the **tolerance in force at the time** (already on `Reconciliation.toleranceApplied` since 5.4; a test proves it survives a later settings change). Added `supplier_id` to the reconciliation (Flyway V37) for per-supplier variance-trend context without a PO join. Consolidated reads (`ReconciliationQueryService`): `GET .../reconciliation-detail` (the whole Screen 4 payload in one call — PI + comparison + approval state + audit trail), `GET /api/purchase-orders/{poId}/reconciliations` (per-PO history incl. superseded), `GET /api/reconciliation/pending-approval` (approver queue). Fixed a latent timezone flake in 5.3's `ReconciliationControllerTest` (asserted `LocalDate.now()` vs the UTC-derived generation date). `ModularityTests` green; full `mvn test` 347.
- Feature 5, Story 5.5: added the Approval routing & 2-of-N sign-off subsection — approve/reject a routed PI with the asymmetric rule. **Routing (5.4, direction-independent) and the 2-of-N gate (extra, price-increase-only) stack, not conflated.** Confirmed both open decisions: **any line being a price increase beyond tolerance triggers the gate for the whole PI** (`PiApprovalService#requiresSignOff`, computed live from the stored variance + recorded tolerance), and **self-approval is prevented** (PO creator via new `PurchaseOrderService#getCreatedBy`, or PI logger — flagged the small-company deadlock caveat for the PO). Single-approver path = `APPROVER` role only (`@PreAuthorize`); 2-of-N path adds pool membership + distinctness. Immutable `ApprovalAction` audit rows (who/when/reconciliation-seen/comment, Flyway V35); PI → `APPROVED` on threshold / `REJECTED` on one rejection. Stranded-pool guard from 5.6 surfaced via `approval-state` (`approvable`/`blockedReason`, threshold never auto-lowered). Approver notification is the **third** consumer of 4.7's `EmailSender` seam (stub, console). Added `PI_NOT_AWAITING_APPROVAL`/`NOT_IN_APPROVER_POOL`/`SELF_APPROVAL_FORBIDDEN`/`ALREADY_SIGNED_OFF`; extended `ApproverPoolService` with `resolveEligibleApproverEmails` (both new cross-module surfaces are additive `@NamedInterface` methods). First `reconciliation → onboarding` module dependency (via the approver-pool named interface); `ModularityTests` green.
- Feature 5, Story 5.6 (built before 5.5, which needs a pool to route to): added the Approver pool configuration subsection — the named pool + required sign-off count that underpins the 2-of-N gate. **Built in the `onboarding` module, not `reconciliation`** (company/user governance; ADMIN-managed alongside roles; active-`APPROVER` validation stays internal), consumed by 5.5 via `ApproverPoolService`'s `@NamedInterface("approver-pool")` surface (`resolveEligibleApprovers`/`requiredSignOffCount`). Explicit membership (`approver_pool_members`, Flyway V33) requiring the `APPROVER` role — not role-derived — and a per-company required count (`approver_pool_settings`, V34, default 2). ADMIN-only config endpoints under `/api/onboarding/company/{companyId}/approver-pool`; reads open. Added `INELIGIBLE_APPROVER` (not an active APPROVER) and `APPROVER_COUNT_EXCEEDS_POOL` (the load-bearing "count must not exceed active pool size" invariant, enforced on set-count and on remove). Deactivation handled at use time (`resolveEligibleApprovers` filters ineligible members; pool never mutated on deactivation). Flagged for 5.5: static validation can't stop a pool being deactivated below its count *after* routing — 5.5 must detect/surface an un-approvable routed PI at runtime.
- Feature 5, Story 5.4: added the Tolerance evaluation & auto-confirm subsection — the auto-confirm-vs-route decision, run right after 5.3 on the same post-log seam (`ToleranceEvaluationService#evaluate`). **The ±2% tolerance boundary is now settled and built: EXCLUSIVE (`|variance| < tolerance`, strictly), per-account configurable (default 2%, `tolerance_settings` + `GET/PUT /api/reconciliation/tolerance`, ADMIN to set), isolated in `ToleranceEvaluator` with a dedicated exact-at-tolerance boundary test.** Updated the Money section's tolerance note (was "still open") accordingly, and recorded the Roadmap-v2-vs-PO conflict resolution (roadmap said per-company/supplier ±3%; built the PO's per-account 2% and flagged the roadmap wording to correct). Whole-PI outcome (any failing line, structural finding, or currency mismatch routes the whole PI; per-line detail retained), recorded as `outcome`/`toleranceApplied` on `Reconciliation` (Flyway V31) with the PI `status` transitioned to match and routing reasons derived on the read model; auto-confirm recorded as a system action; currency mismatch routes with no FX conversion. Added `ReconciliationOutcome`/`RoutingReason`/`ToleranceSetting`. Only price variance gates tolerance (quantity tracked separately, doesn't route on its own in MVP — flagged as the deliberate reading). No new error codes.
- Feature 5, Story 5.3: added the Variance calculation subsection — the three-way PO/PI/price-file comparison, computed per line and recorded (no tolerance/routing decision, that's 5.4). Runs off 5.2's post-log seam (`ReconciliationTriggerService` now delegates to `ReconciliationService#reconcile`). Added `Reconciliation`/`ReconciliationLine` (Flyway V29/V30), `VarianceCalculator` (pure, unit-tested), and a read endpoint `GET /api/proforma-invoices/{piId}/reconciliation`. **Settled the last open Feature 5 question in build:** variance basis is unit price (PI-vs-PO, signed for direction, HALF_EVEN 2dp before storage), isolated in `VarianceCalculator.BASIS` so "line total" is a one-line change and recorded per-reconciliation — pending only PO confirmation the default's right; the ±2% tolerance boundary is now the one input 5.4 still needs. Quantity tracked as its own comparison (abs + %); structural findings (unmatched PI/PO line, duplicate SKU) detected and surfaced, not dropped; currency mismatch flagged with no FX conversion (unit-price variance left null, quantity still computed); variance stored on every matched line regardless of magnitude (PO's drift-trending ask). New cross-module surface `PurchaseOrderService#getReconciliationView` (`@NamedInterface("purchase-orders")`) — the PO leg's snapshot, without exposing the PO domain.
- Feature 5, Story 5.2: added the Logging a PI subsection — `POST/GET .../proforma-invoices` and `GET /api/proforma-invoices/{id}`. The story's central principle, "record faithfully, judge later": validation is well-formedness only (real SKU, valid 4dp price, positive quantity, real ISO currency code) — price/quantity/currency disagreement with the PO is recorded, never rejected, since that disagreement is what reconciliation (5.3+) exists to detect. Added `PO_NOT_READY_FOR_PI` (`DRAFT` PO blocked, `GENERATED`/`SENT` both allowed) and two new cross-module surfaces: `PurchaseOrderService#assertOwnPurchaseOrderExists`/`#assertOwnPurchaseOrderReadyForPi` (`@NamedInterface("purchase-orders")`, first time this module needed one) and `SkuService#assertOwnSkuExists` (company-wide, deliberately not scoped to the PO's own supplier — a cross-supplier SKU reference is a discrepancy for 5.3 to surface, not an entry-time rejection). Implemented 5.1's cardinality decision for real via `ProformaInvoice#supersede`. Built the post-log reconciliation trigger as an actual transactional seam (`ProformaInvoiceRecordingService`'s own commit, then a separately-called, swallow-on-failure `ReconciliationTriggerService` stub) rather than just a comment describing the intent, and structured the whole write behind one `ProformaInvoiceService#log` entry point so a future AI-extraction feed (Feature 8) can call the same method a human's request goes through today.
