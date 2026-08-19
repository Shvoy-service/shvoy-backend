# Story A9 — SES email (dev): setup checklist

Wires Amazon SES behind the `EmailSender` seam (Story 9.4). The code is done and
profile-selected — `local`/`test` keep `ConsoleEmailSender`, `dev`/`prod` use
`SesEmailSender`. This checklist is the AWS/console half: verify a sender
identity, acknowledge the sandbox, grant the task role `ses:SendEmail`, and set
the sender-address env var. Region `eu-west-2`, account `038774852612`, A1
conventions.

> **Two truths that shape this:**
> 1. **New SES accounts start in the sandbox** — you can only send *to* verified
>    addresses. Fine for dev (verify the team's emails). Production sending needs
>    a **sandbox-exit request** — a support ticket with lead time, deferred to the
>    prod-infrastructure story (recorded in §5 so it isn't discovered launch week).
> 2. **Email is best-effort** — the code guarantees a send failure never breaks
>    the business action (the invite still creates). The sandbox makes this
>    immediately real: the first unverified recipient in dev *will* fail, and that
>    must be a recorded `FAILED_PERMANENT`, not a broken invite.

---

## 1. Verify a sender identity

Prefer the **domain** if you control its DNS — one DKIM setup covers every
from-address you'll ever want (`invites@`, `noreply@`). A single verified
**address** is the fallback if domain DNS is awkward right now.

- [ ] **SES console (`eu-west-2`) → Identities → Create identity.**
  - **Domain** (recommended): enter your product domain, enable **Easy DKIM**,
    and add the 3 CNAME records SES gives you to the domain's DNS. Verification
    completes when DNS propagates (minutes to a few hours). Then any
    `something@your-domain` works as the from-address.
  - **Or Email address** (fallback): enter e.g. `noreply@your-domain`, click the
    verification link SES emails you.
- [ ] Note the from-address you'll use (e.g. `noreply@your-domain`) — it becomes
  the `EMAIL_SENDER` env var in §4.

> The from-domain interacts with the still-open custom-domain question — verifying
> the **real product domain** now is fine even while the app URL is still the ECS
> `sh-<hash>.ecs...` host. The from-domain and the app URL are independent.

---

## 2. Sandbox: verify the dev recipients

While in the sandbox, SES only delivers to **verified** destinations.

- [ ] **Identities → Create identity → Email address** for each dev tester's
  address (yours + anyone who'll receive test mail). Click each verification link.
- [ ] That's enough for all dev testing (invite yourself, send a PO to a verified
  address, etc.). Unverified recipients will `FAILED_PERMANENT` — which is the
  correct, tested behaviour, not a bug.

---

## 3. Grant the ECS task role `ses:SendEmail`

Add to the **`shvoy-dev-ecs-task-role`** inline policy (the accumulated task-role
permissions, per A7). Scoped to the verified identity — no `*`:

```json
{
  "Sid": "SesSend",
  "Effect": "Allow",
  "Action": ["ses:SendEmail"],
  "Resource": "arn:aws:ses:eu-west-2:038774852612:identity/YOUR_VERIFIED_IDENTITY"
}
```
- `YOUR_VERIFIED_IDENTITY` is the domain (`your-domain`) or the address
  (`noreply@your-domain`) you verified in §1. SES uses the task role at runtime —
  **no SMTP credentials, none in code.**

---

## 4. Set the sender-address env var

`SesEmailSender` reads `${email.sender}`, which `application-dev.yml` binds to the
`EMAIL_SENDER` environment variable.

- [ ] Add `EMAIL_SENDER=noreply@your-domain` to the **`shvoy-dev-backend` ECS task
  definition** environment (a plain env var, like `COGNITO_*` — not a secret).
  Register a new task-def revision and deploy (the *Deploy to dev* workflow).
- [ ] (Prod later: `application-prod.yml` will need the same `email.sender` binding
  when the prod environment is stood up — noted in §5.)

---

## 5. Verify end-to-end (the four consumer flows)

With a deployed task and a verified recipient, confirm each of the four flows
delivers a real email (ugly stub-strings until 9.5 gives them real content — that's
expected):
- [ ] **Invite** — `POST /api/onboarding/company/{id}/invite` to a *verified*
  address → email arrives; a `send_records` row with `source=INVITATION`,
  `outcome=SENT`, a `ses_message_id`.
- [ ] **PO send** — `POST /api/purchase-orders/{id}/send` (supplier contact must be
  a verified address in the sandbox) → email with the PDF attached.
- [ ] **Approval request** — route a price-increase PI for approval → each eligible
  approver (verified) gets the notice.
- [ ] **Discrepancy** — open a discrepancy case → the resolver pool (verified) gets
  the notice.
- [ ] **The failure path, on purpose:** invite an *un*verified address → the invite
  still creates, and a `send_records` row shows `outcome=FAILED_PERMANENT` with the
  SES error. This is the story's core guarantee, seen live.

---

## Deferred to the prod-infrastructure story (record now, don't chase)

- [ ] **Sandbox exit** — a support request ("Request production access") in the SES
  console; has **lead time** (often ~24h, can be longer). Do it well before any
  prod launch, not the week of.
- [ ] **Bounce & complaint handling** via SNS — real SES hygiene (a high
  bounce/complaint rate gets sending paused). Prod-era; note it beside sandbox exit.
- [ ] **Prod `email.sender`** binding in `application-prod.yml` + the prod task
  role's `ses:SendEmail`.

## Decisions Log

| Decision | Value | Why |
|---|---|---|
| SDK | SESv2 (`software.amazon.awssdk:sesv2`), simple content | Simple content now carries attachments natively (the PO PDF) — no raw MIME |
| Sender identity | Domain (Easy DKIM) preferred; address as fallback | One DKIM covers every from-address; survives the custom-domain decision |
| Auth | ECS task role `ses:SendEmail` scoped to the identity | No SMTP, no credentials in code — same pattern as Cognito/S3 |
| Send mode | Synchronous, no retry/queue | Trivial volumes; failure-isolation makes sync safe; a failed send's record is the retry mechanism (a human resends) |
| Failure isolation | `SesEmailSender` catches everything, never throws | Consumers call `send` with no try/catch — fire-and-forget (4.7's contract, enforced under real failure) |
| Send record | `send_records`, append-only, subject + metadata only | Answers "did it send?"; **never the body** (invite links — token hygiene) |
