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
| `DUPLICATE_SKU` | 409 | A SKU with this code (case-insensitive) already exists for this supplier |
| `AMBIGUOUS_PRICE_WINDOW` | 409 | A new SkuPrice's validity window can't be reconciled with a SKU's existing prices without guessing (backdated/duplicate start, an overlap, or a gap) — see SKU & price entry/upload below |
| `CURRENCY_MISMATCH` | 409 | A PO line resolved to a different currency than the PO's existing lines — see Purchase orders below |
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
- **Fields:** `depositPercentage` (`BigDecimal`, 0–100 inclusive, fractional values allowed e.g. `33.5`) is the only percentage stored. `balancePercentage` in the response is always `100 − depositPercentage`, computed on read — never an independent stored/validated field, so the two can't drift out of sync. `anchorEvent` is one of `BL`/`INVOICE`/`ARRIVAL`; `daysOffset` is a non-negative integer.
- **Model:** a separate `payment_terms` table keyed directly by `supplier_id` (shared primary key, no generated id of its own), not inline columns on `suppliers` — `Supplier`'s own Story 3.1 Javadoc already committed to payment terms being a separate entity, same as prices/SKUs and discount tiers.
- Mutation (`PUT`) is `ADMIN`/`PURCHASING`-only; `GET` is open to any authenticated role — same role split as Suppliers.
- Tenancy: the supplier must belong to the caller's company; a cross-tenant supplier id (or a nonexistent one) returns `404`/`NOT_FOUND` on both `PUT` and `GET`.

### The split rule (deposit/balance allocation)

To split an order total by a supplier's terms: `deposit = round(total × depositPercentage / 100)` at `Money`'s standard scale-2/**HALF_EVEN** rounding (via `Money.multiply`, not a separate rounding rule — see Money below), then `balance = total − deposit` (via the new `Money.minus`), **not independently rounded**. This guarantees `deposit + balance` always equals the total exactly, with any odd remainder falling on the balance rather than the deposit. Implemented as `PaymentTerms#split(Money total)`; not wired to any endpoint yet — Feature 7 is the first real caller.

**Decisions made on recommended defaults, pending Product Owner confirmation:**
- **Deposit precision:** stored as a decimal supporting fractional percentages (e.g. `33.5%`), not integer-only. Costs nothing now; tightens to whole-number-only via validation, no migration, if the PO says otherwise.
- **Remainder placement:** the balance absorbs the odd penny, not the deposit (deposit stays a clean rounded figure). One-line change (which side is derived) if the PO wants it the other way.

Both are isolated, low-cost-to-reverse choices confined to `PaymentTerms#split`.

---

## Money

**Owner:** Cleanup Story 4 (money serialisation & rounding rule).

- **Wire format:** a string amount plus an explicit currency code — `{"amount": "1234.56", "currency": "USD"}`. Never a bare JSON number (floats can't represent decimal currency exactly, and different JSON parsers handle numeric precision differently across languages — a string sidesteps both).
- **Internal type:** `BigDecimal` server-side, never `double`/`float`, for every monetary value.
- **Rounding mode:** `HALF_EVEN` ("banker's rounding") — not `HALF_UP`. Ties round to whichever neighbor is even (`0.125` → `0.12`, `0.135` → `0.14`), rather than always rounding away from zero.
- **Rounding step:** each line-level amount is rounded to 2 decimal places the moment it's computed; totals are sums of already-rounded values, never a sum of full-precision intermediates rounded once at the end. This means displayed line items always sum to the displayed total — the alternative (round-only-the-final-sum) can be a cent off from what's shown.
- **Implementation:** `com.shvoy.Money` (record: `amount` + `currency`) is the monetary type for currency-minor-unit amounts (totals, deposits, balances) — every such field should be a `Money`, not a raw `BigDecimal`. Its compact constructor enforces scale-2/`HALF_EVEN` on construction, including on every result of `plus`/`minus`/`multiply`, so the rounding-step rule above is structural rather than a convention each call site has to remember. Wire (de)serialisation to/from the string format is built in (`AmountSerializer`/`AmountDeserializer`, shared with `UnitPrice` below) — no per-field `@JsonFormat` needed anywhere `Money`/`UnitPrice` is used.
- **`com.shvoy.UnitPrice` — the sibling type for per-unit prices (added Story 3.4):** same record shape, wire format, and `HALF_EVEN` rounding as `Money`, but fixed at **scale 4** instead of 2. Procurement unit prices routinely carry 4 decimal places (e.g. `1.4275`); rounding a unit price down to 2dp before multiplying by a large order quantity would compound into a real total-price error, so it's a distinct type rather than `Money` used at a different scale. `Money` stays fixed at 2dp everywhere it's used — nothing about this introduces a variable-scale `Money`.

**Still open, deferred to Feature 3 (no monetary fields existed in the codebase when Cleanup Story 4 was written, so these didn't have a concrete case to resolve against — SKU unit prices, added Story 3.4, are the first, but the questions below are about `Money`/totals, not `UnitPrice`):**
- **Currency scope** — is SHVOY single-currency (e.g. USD only) for the pilot, or does multi-currency need to actually work? `Money`/`UnitPrice` carry a currency code either way (validated as a real ISO 4217 code), but no conversion logic exists, and none is planned until this is answered.
- **The ±2% tolerance boundary** — the PO/price-reconciliation matching rule that motivated this story in the first place. Needs to be spelled out concretely (which comparison, which fields) once Feature 3's price-resolution service (3.8) exists, and a boundary test written against that actual rule — `MoneyTest` currently proves the rounding policy in isolation (see `roundsEachLineThenSumsRatherThanSummingRawAndRoundingOnce`), not that specific business rule.

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

### Resolution logic

1. **Price:** the `SkuPrice` whose validity window contains `asOfDate`, via the existing `SkuPrice#isInDate` (3.4) — not reimplemented. The 3.5 supersession rule keeps a SKU's prices non-overlapping, so normally at most one matches.
2. **Tier:** from that price's tiers, the **highest threshold ≤ quantity** (tiers are monotonically non-increasing per 3.6, so the highest applicable threshold is always the correct price); no match means the base price applies.
3. **Carton:** independent of the above — see the table.

### Defensive handling: overlapping windows

Two `SkuPrice` rows matching the same `asOfDate` should never happen given 3.5's guards, but resolution doesn't assume the invariant always holds. If it ever does happen, the row with the **latest `validFrom`** wins (deterministic, not arbitrary), and a `log.warn` fires — since more than one match means the non-overlapping supersession invariant was violated somewhere upstream, which is worth knowing about, not silently papering over.

### Explicitly out of scope here

Multi-currency conversion/comparison (the resolved price simply carries its `SkuPrice`'s currency; comparing/converting across currencies is a flagged Product Owner decision that belongs to Feature 5's reconciliation scope — see Money above). The Screen 3 "blocks submit until overridden" UI behavior and any PO logic (Feature 4 — this service only supplies the `priceFound`/carton signals that behavior reads). Any price mutation (this service never writes).

---

## Purchase orders

**Owner:** Story 4.1 (PO data model) and 4.2 (line pricing & validation). Still no create/edit endpoints (4.4), no generation/sending — `PurchaseOrder`/`PurchaseOrderLine` are reachable only via direct repository/service access until then.

- `PurchaseOrder` — tenant-scoped, one `supplier_id` (a PO is to exactly one supplier), a per-company sequential `po_number` (see below), `status` (`DRAFT`/`GENERATED`/`SENT`, string not ordinal, modelled for clean extension by later features), nullable `requested_etd` (`LocalDate`), `created_by` (plain `UUID` referencing `users.id`, no JPA relationship — same flat-column convention as `Supplier`/`Sku`).
- `PurchaseOrderLine` — tenant-scoped, linked to its `PurchaseOrder` and a `sku_id`, `line_number` (stable display order) and `quantity` (both required at creation, whenever 4.4 adds the endpoint that creates one), plus the **price snapshot** fields below.

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

**Validation, mostly enforced by reuse rather than new checks:** quantity must be positive (`VALIDATION_ERROR`) — checked explicitly here even though `PriceResolutionService` already checks it too, deliberate defense-in-depth for a caller that might reach this service some other way. A SKU that doesn't belong to the PO's supplier surfaces as `NOT_FOUND` **for free**, via `PriceResolutionService`'s own ownership chain — not reimplemented in this service. **New in this story:** a single-currency-per-PO rule — a line that resolves to a different currency than the PO's other already-priced lines is rejected (`CURRENCY_MISMATCH`/409). A clean, defensible constraint for PO creation (sidesteps the harder multi-currency question, which belongs to Feature 5 reconciliation — see Money above), but flagged as a real decision pending confirmation it fits how SHVOY's suppliers actually quote.

### PO number generation

Per-company sequential, `PO-0001` style (`PoNumberGenerator`) — matches the wireframes and what users expect to reference a PO by, unlike a UUID-derived code. Unique **per company** (`V18`'s composite index on `company_id, po_number`), not globally — two different companies each having their own "PO-0001" is correct, not a collision.

Race-safe under concurrent claims for the same company via a `SELECT ... FOR UPDATE` lock on a dedicated `po_number_counters` row (one per company, owned by the purchaseorders module rather than added to `companies`, which belongs to onboarding) — same lock-based approach as `SkuService#lockSkuForPriceWrite` (see SKU & price entry/upload above). Ensuring that counter row exists and then locking/incrementing it run as two **sequential, non-nested** steps, deliberately: an earlier version isolated the row-creation step in its own `REQUIRES_NEW` transaction so a lost race there couldn't poison the claim's transaction (Postgres aborts an entire transaction after any failed statement — catching the exception and continuing in the *same* transaction only happens to work under H2, not for real against Postgres), but `REQUIRES_NEW` suspends rather than releases the caller's connection, so every concurrent caller needed two connections held at once — confirmed the hard way when 10 concurrent claims deadlocked a 10-connection pool outright. Running the two steps sequentially instead means each caller only ever holds one connection at a time.

---

## Dates and timestamps

**Owner:** Cleanup Story 5 (date field mapping & container-fill deadline timezone). Field-level mapping is now **settled** — Story 3.4 gave the rule its first concrete case (SKU price validity dates), so this is no longer a rule with nothing to point at. Only the container-fill deadline timezone remains open, parked until Feature 8.

Rule: business dates are `LocalDate` (serialises as `yyyy-MM-dd`), real points in time are `Instant` (serialises as ISO-8601 UTC).

| Field | Type | Status |
|---|---|---|
| SKU price validity dates (`valid_from`/`valid_to`) | `LocalDate` | Implemented — Story 3.4, `SkuPrice` |
| Requested ETD | `LocalDate` | Not yet implemented (Feature 4) |
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
