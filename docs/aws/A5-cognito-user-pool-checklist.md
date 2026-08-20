# Story A5 — Cognito User Pool (Dev): Console Checklist

Console-driven, same as A1–A4. Region: `eu-west-2`. Doesn't depend on A2/A3's networking — like S3, Cognito isn't VPC-bound.

**The one setting that can't be changed after creation — get this right:** our merged `CognitoIdentityProvider.createConfirmedUser` calls `AdminCreateUser` with `.username(email)` — it passes the email address itself as Cognito's `Username`. That only works under Cognito's **"username attributes"** mode (select **only Email** as the sign-in identifier, leave **Username unchecked**). If you check both "Username" and "Email," Cognito switches to **alias** mode instead, and alias mode explicitly *rejects* a Username formatted like an email address — which would break every admin-created user in this app, silently, at the one point you can't fix without recreating the pool. Confirmed directly against current AWS docs, not a search summary (an earlier summary I pulled actually suggested the wrong option here).

---

## 1. Create the user pool

- [ ] **Cognito console → User pools → Create user pool**.
- [ ] **Configure sign-in experience** → **Options for sign-in identifiers**: check **Email** only. **Do not check Username.** (See the callout above — this is the irreversible one.)
- [ ] **Configure security requirements** → **Password policy**: custom, minimum length **12** (matches `ActivateAccountRequest`'s `@Size(min = 12)` — a password that passes our app's own validation must also pass Cognito's, or `adminSetUserPassword` will reject it after our validation already accepted it). Leave AWS's default complexity requirements on (uppercase, lowercase, number, special character) — nothing in our code assumes otherwise, but **you must use a password satisfying both rules during Section 4's live verification** (e.g. `CorrectHorseBattery123!`, not a plain lowercase phrase).
- [ ] **MFA**: **No MFA** — explicitly out of scope for the pilot.
- [ ] **Configure sign-up experience**:
  - Self-registration: **disable it** (`AllowAdminCreateUserOnly=true`). The original guidance here was "doesn't matter either way — our app never uses Cognito's own public sign-up, only `AdminCreateUser`. Leave the default." That reasoning was half right: the *app* never calls `SignUp`, but leaving it enabled lets anyone create pool users directly against the public API — which happened for real on the dev pool (2026-08-20): a teammate self-signed-up, got a confirmed Cognito identity with no SHVOY profile behind it, and that stale identity then blocked the proper activation flow for their email until deleted by hand. Costs nothing to disable; already disabled on the dev pool.

    For an existing pool: `aws cognito-idp update-user-pool` resets every unspecified mutable setting to its default — never call it with just the one flag. Describe first and replay at least the password policy alongside the change (see A9-era runbook note, or the dev-pool command recorded in the PR that introduced this line).
  - Required attributes: check **email**.
  - Cognito assigns `email_verified` regardless — our code sets it directly via `AdminCreateUser`'s attributes, so the pool's own "auto-verify" self-service setting doesn't matter for our flow either way.
- [ ] **Configure message delivery**: default (Cognito/SES for the pilot) is fine — we suppress Cognito's own emails anyway (`MessageActionType.SUPPRESS` in `CognitoIdentityProvider`), so this only matters for anything Cognito might still need to send later (e.g. a future password-reset story, out of scope here).
- [ ] **Integrate your application**:
  - User pool name: `shvoy-dev-user-pool`.
  - **Hosted UI**: not needed — the integration was built for custom screens, not Cognito's hosted UI.
  - You'll create the app client in the next section rather than accepting whatever default this step proposes — see Section 2.
- [ ] **Pricing tier**: accept the default (**Essentials**, confirmed current default for new pools) — a pilot is nowhere near the 10,000 free-tier MAU ceiling either tier offers, and Essentials includes more capability at the same $0 cost at this scale. No reason to actively downgrade to Lite.
- [ ] Tags: `env=dev`, `project=shvoy`, `owner=<you>`.
- [ ] **Create user pool.**
- [ ] Note the **User pool ID** (format `eu-west-2_XXXXXXXXX`) — record it in the Decisions Log below.

---

## 2. Create the app client

Created automatically as part of Step 1's consolidated wizard (the current Cognito console bundles pool + first app client into one flow — see conversation notes). This section is a **verification/adjustment pass** on what the wizard already created, not a from-scratch creation.

- [x] App client name: `shvoy-dev-app-client`.
- [x] **Client secret: none** — confirmed empty, matching the public-client decision.
- [ ] **Authentication flows** — the "Single-page application" default enabled **Choice-based sign-in** (`ALLOW_USER_AUTH`), **SRP** (`ALLOW_USER_SRP_AUTH`), and **refresh tokens** (`ALLOW_REFRESH_TOKEN_AUTH`), but *not* the classic single-call password flow. Go to **App client → Edit → Authentication flows** and additionally enable:
  - **"Sign in with server-side administrative credentials"** (`ALLOW_ADMIN_USER_PASSWORD_AUTH`) — needed for Section 5's own verification. This is IAM-authorized (only callable with real AWS credentials, like the `shvoy-dev` SSO profile), not public — the app itself never calls this, only this checklist's manual verification does, via `admin-initiate-auth`.
- [x] **Token expiration**: Access 60 min, ID 60 min, **Refresh 5 days** (shorter than the 30-day default I originally assumed — not a problem, nothing in the resource-server code depends on a specific duration, correcting the record here rather than changing it).
- [ ] Leave OAuth/hosted-UI-specific settings (callback URLs, OAuth grant types, scopes) at their defaults/unconfigured — not used, since there's no hosted UI.
- [ ] **Create app client.**
- [ ] Note the **App client ID** — record it in the Decisions Log below.

---

## 3. Wire the dev profile config

No code change needed here — `application-dev.yml` already has these as env-var-backed properties with no default (from the Cognito Integration story):
```yaml
cognito:
  user-pool-id: ${COGNITO_USER_POOL_ID}
  app-client-id: ${COGNITO_APP_CLIENT_ID}
```
- [ ] Record the real values in the Decisions Log below — these become the `COGNITO_USER_POOL_ID` / `COGNITO_APP_CLIENT_ID` environment variables wherever the app runs under `dev` (this checklist's Section 4 locally, and A7's ECS task definition later).
- [ ] JWKS/issuer URI: also no config needed — `SecurityConfig.cognitoJwtDecoder` already derives `https://cognito-idp.{region}.amazonaws.com/{user-pool-id}` in code from `aws.region` + `cognito.user-pool-id`, so setting the pool ID above is sufficient; there's no separate JWKS property to fill in.

---

## 4. IAM permissions for A7 (documented here, not created yet — no ECS task role exists until A7)

The exact three actions `CognitoIdentityProvider.java` calls, scoped to this specific pool's ARN only:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "CognitoAdminUserManagement",
      "Effect": "Allow",
      "Action": [
        "cognito-idp:AdminCreateUser",
        "cognito-idp:AdminSetUserPassword",
        "cognito-idp:AdminDeleteUser"
      ],
      "Resource": "arn:aws:cognito-idp:eu-west-2:038774852612:userpool/<user-pool-id>"
    }
  ]
}
```
(Substitute the real user pool ID from Step 1 into the ARN before A7 uses this.)

---

## 5. The real validation — what's done now vs. deferred to A7

**Original plan abandoned, and worth recording why:** the plan below (open a temporary security-group rule for local access) doesn't actually work, for a reason that only became clear by trying it. `shvoy-dev-rds` has **"Publicly accessible: No"**, which means its endpoint hostname resolves via public DNS to its **private VPC IP** (confirmed: `shvoy-dev-rds.cl08imme8ei6.eu-west-2.rds.amazonaws.com` → `10.0.1.237`), not a public one. A security group only permits or denies traffic that already has a route to the resource — it can't create a route that doesn't exist. So no SG rule, scoped to one IP or wide open, was ever going to let an external client reach this instance. This isn't an A2/A3 misconfiguration — it's "Publicly accessible: No" working exactly as intended — it just means `/register`, `/invite/accept`, real protected-endpoint token validation, and the deactivation check **cannot be exercised from outside the VPC at all**, by design, until something inside the VPC (A7's ECS task) can reach it.

**Decision: defer the full end-to-end flow to A7.** The two temporary SG rules opened while diagnosing this were removed immediately once the real cause was found — `shvoy-dev-rds-sg` is confirmed back to its exact A2/A3 state (the single ECS-SG-only rule, nothing else).

### 5a–5d (renumbered from the original plan): what to do at A7 instead
When A7 stands up the ECS task (inside the VPC, so it can actually reach RDS), run these against that deployed task rather than a local `dev`-profile run:
- [ ] **Registration:** `POST /api/onboarding/register` → `activate` with a compliant password → confirm `cognito_sub` populated, real Cognito user `CONFIRMED`.
- [ ] **Invite/accept:** same shape, second user, confirm atomic activation.
- [ ] **Token validation:** real access token (see 5f-partial below for the exact CLI shape that works) against a protected endpoint → confirm `200`, correct `company_id`/`role`.
- [ ] **Deactivation:** deactivate a user, confirm their still-valid Cognito token now gets `401`/`UNAUTHENTICATED` from SHVOY, not Cognito.
- [ ] **Bonus proof this still gets for free at A7, not lost by deferring:** A7's first successful boot against real RDS is still the first time Flyway runs there — the "Flyway populates the empty instance" proof from A3 lands then, just later than originally planned.

### 5e. What *was* verified now — partial, Cognito-only, no RDS needed
Since the app itself can't run against real RDS from here, I verified the Cognito half directly via AWS CLI, bypassing the app entirely — real signal, not nothing:
- [x] **`AdminCreateUser` + `AdminSetUserPassword(permanent=true)`**, the exact call shape `CognitoIdentityProvider.createConfirmedUser` uses (suppressed message, temp password, then permanent) — ran against the real pool with a throwaway test user. Result: `UserStatus` went `FORCE_CHANGE_PASSWORD` → `CONFIRMED` correctly, matching what the mock always did.
- [x] **A real access token**, obtained via `admin-initiate-auth --auth-flow ADMIN_USER_PASSWORD_AUTH` (see the Decisions Log for why this flow specifically, and why it needed adding to the app client after the fact). Decoded and checked against exactly what `CognitoJwtDecoder`/`CognitoJwtAuthenticationConverter` depend on:
  - `iss`: `https://cognito-idp.eu-west-2.amazonaws.com/eu-west-2_eNrQ3aPRK` — exact match for what `SecurityConfig.cognitoJwtDecoder` constructs from `aws.region` + `cognito.user-pool-id`.
  - `client_id`: matches the app client ID exactly.
  - No `aud` claim present — confirms the custom `CognitoClientIdValidator` is validating something the standard/default validators genuinely wouldn't have caught, not redundant.
  - `sub`: present, stable, exactly what `CognitoJwtAuthenticationConverter` looks up by.
- [x] Test user deleted afterward (`admin-delete-user`) — pool left clean, no stray test accounts.

This doesn't prove the SHVOY-side wiring (profile resolution, tenancy, the `ACTIVE` check) — only that every real-Cognito input those checks depend on is shaped exactly as the code assumes. The SHVOY-side proof is what A7 completes.

---

## Decisions Log

| Decision | Value | Why |
|---|---|---|
| Sign-in identifier | Email only, Username unchecked | Matches `.username(email)` in `CognitoIdentityProvider` — the other option (alias) rejects email-formatted usernames outright |
| Password policy | Cognito default (min 8, upper/lower/number/symbol required) — left unchanged, verified via CLI | App's own `@Size(min=12)` is already stricter than Cognito's 8-char minimum; complexity rules mean the test password in Section 5 needs upper/lower/number/symbol |
| Pricing tier | Essentials (default) | Pilot is far under the 10K free-tier MAU ceiling either way; no reason to actively downgrade |
| App client secret | None (public client) | Confirmed decision from the Cognito Integration story |
| Auth flows enabled | Choice-based (USER_AUTH), SRP, refresh token (console defaults) + ADMIN_USER_PASSWORD_AUTH | SPA default didn't include a single-call CLI-friendly flow. First attempt to enable this via console didn't take (confirmed missing via `describe-user-pool-client`); added directly via `update-user-pool-client` CLI instead, preserving the existing three |
| Token validity | Access 60min, ID 60min, **Refresh 5 days** (console default, not 30) | Nothing in the code depends on a specific duration; corrected from my original assumption |
| User pool ID | `eu-west-2_eNrQ3aPRK` | — |
| App client ID | `rgn7bv1viifbp0h10pesdo2pu` | — |
| RDS temp access | **Abandoned as a concept, not just closed out** | `PubliclyAccessible: false` means the endpoint resolves only to a private VPC IP — no SG rule (IP-scoped or `0.0.0.0/0`, both tried) can make that reachable from outside the VPC. Both temporary rules opened during diagnosis were removed; SG confirmed back to its exact A2/A3 state |
| Mock vs. real divergences found | 1) `AdminCreateUser`'s actual `Username` becomes a Cognito-generated UUID, not the email string passed in — **not a bug**: Cognito's "email in place of username" rule (documented) means `AdminSetUserPassword`/`AdminDeleteUser` with `.username(email)` still resolve correctly, confirmed live. 2) Full end-to-end (register/invite/token/deactivation) can't be exercised outside the VPC at all — deferred to A7, see Section 5 | Both found by actually running things against real Cognito/RDS rather than assuming, exactly per the story's own framing |

---

## Acceptance criteria checklist (from the story)

- [x] A Cognito user pool exists in `eu-west-2` with A1 tags, configured to match the merged integration (email sign-in as username attribute — verified via CLI, password policy, no hosted UI)
- [x] App client exists with no client secret, token settings consistent with in-memory-access-token/SDK-refresh
- [x] `dev` profile config carries real pool ID/client ID (env vars); JWKS/issuer derived automatically, no separate config needed
- [ ] **Deferred to A7:** Registration against dev creates both a SHVOY profile and a real Cognito user, `cognito_sub` stored — Cognito-side mechanics verified directly (Section 5e), SHVOY-side proof needs RDS access
- [ ] **Deferred to A7:** Invite/accept against dev provisions the real Cognito user and activates the profile atomically
- [ ] **Deferred to A7:** A real Cognito JWT validates against a protected endpoint and resolves to the correct profile/company/role — token shape/claims verified directly (Section 5e), SHVOY-side resolution needs RDS access
- [ ] **Deferred to A7:** An INACTIVE profile cannot authenticate even with a valid token
- [x] Required Cognito admin permissions documented for the A7 task role (Section 4)
- [x] Mock-vs-real divergences identified and resolved/documented (Decisions Log) — none required a code change; both were either a documented Cognito behavior or a scope/sequencing finding, not a bug
