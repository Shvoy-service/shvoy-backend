# Story A1 — Account Guardrails & Billing Safety: Console Checklist

Console-driven, not IaC (decided for this story specifically — see `docs/CONTRACT.md`-style rationale: most of this is irreducibly manual — MFA enrollment, root lockdown, confirming an alert email actually arrives — so a checklist is the honest fit here. A2 onward, once there's actual infrastructure to declare, can revisit IaC). Steps verified against current AWS documentation as of this story, not recalled from memory — console layouts and defaults do shift.

**No application infrastructure is created in this story.** If you find yourself creating a VPC, RDS instance, S3 app bucket, or anything else — stop, that's A2+.

---

## 0. Region decision — do this first

**Decision: `eu-west-2` (London).**

This isn't just a tag on later resources — IAM Identity Center itself asks you to pick a **"home Region"** the first time you enable it, and that choice is effectively permanent (changing it later means deleting and recreating the whole Identity Center configuration, including all users/permission sets). So the region call has to be made before Step 1, not after.

- [ ] Note that `eu-west-2` is the decision, and use it consistently as the console region selector for every step below **except** the CloudWatch billing alarm (Step 3b), which is a hard AWS platform exception — that metric only ever exists in `us-east-1`, regardless of your chosen region. This is a genuine AWS limitation, not a mistake if you see it.
- [ ] Document this decision somewhere durable (this file serves that purpose — see the Decisions Log at the bottom).

---

## 1. Stop using the root account for daily work

### 1a. Root account MFA
- [ ] Sign in as the **root user** (email address used to create the account, not an IAM user).
- [ ] Go to **IAM console → Dashboard**, or directly **My Security Credentials** (top-right account menu).
- [ ] Under "Multi-factor authentication (MFA)", assign an MFA device to root. Use an authenticator app (or a hardware key if you have one) — AWS supports virtual MFA apps (Google Authenticator, Authy, 1Password, etc.).
- [ ] Confirm root has **no access keys**. IAM console → root user security credentials → Access keys section should be empty. If any exist, deactivate and delete them — root should never hold long-lived programmatic credentials.

### 1b. Human admin access via IAM Identity Center (not an IAM user)
AWS's current guidance (checked live for this story) is to use **IAM Identity Center** rather than a long-lived IAM admin user for human access — one MFA enrollment, temporary session credentials that expire, one place to revoke access. Confirmed this does **not** require AWS Organizations for a standalone account — Identity Center can run as a self-contained "account instance."

- [ ] Go to **IAM Identity Center** in the console, region set to `eu-west-2`.
- [ ] Enable Identity Center. If prompted to choose between an organization instance and an account instance, and you're not planning a multi-account AWS Organization yet, the account instance is the simpler fit for a solo/small-team pilot — organization instance is the right call only if you already know you'll want multiple AWS accounts (e.g. separate dev/prod accounts) soon.
- [ ] Confirm/set the **Identity Center home Region** to `eu-west-2` if asked explicitly.
- [ ] Create yourself a user in Identity Center's built-in identity store (Users → Add user) with your real email — this is your day-to-day admin identity going forward, not root.
- [ ] Create a **Permission Set** with `AdministratorAccess` (Identity Center → Permission sets → Create), and assign it to your user against this AWS account.
- [ ] Enable MFA for this Identity Center user too (Identity Center enforces/prompts for this on first sign-in, or configure it under Settings → Authentication).
- [ ] Sign out of root. Sign in going forward via the Identity Center **access portal URL** (shown on the Identity Center dashboard, looks like `https://d-xxxxxxxxxx.awsapps.com/start`) using your new user + MFA.

### 1c. Document root credential custody
- [ ] Write down (in a password manager, not this repo) who holds the root email/password and the MFA device/recovery codes, and where. For a solo founder this can be as simple as "root MFA is on my phone, password is in [password manager]" — the point is it's written somewhere findable, not that it's elaborate.

---

## 2. Billing & cost safety

### 2a. AWS Budget with email alert
- [ ] Go to **Billing and Cost Management console → Budgets** (this is an account-level/global console, not tied to the `eu-west-2` region selector).
- [ ] Create budget → **Customize (advanced)** → Cost budget.
- [ ] Period: **Monthly**, recurring.
- [ ] Budgeted amount: **$120/month** (splitting the story's suggested $100–150 range — the earlier running-stack estimate was ~$75–90/month, so this gives headroom before the alert fires while still catching a real runaway).
- [ ] Add an alert threshold: **80% of actual spend** and a second at **100% of forecasted spend** (both actual and forecast alerts are worth having — actual catches money already spent, forecast catches a trajectory before it happens).
- [ ] Notification email: the address you'll actually check. Add it now.
- [ ] Save the budget.

### 2b. Confirm the alert email actually delivers
An alert nobody receives is worthless — don't skip this.
- [ ] AWS Budgets doesn't have a one-click "send test email" button, so confirm delivery one of these ways:
  - Temporarily set a threshold alert to a tiny amount (e.g. $1 actual spend) on a **duplicate test budget**, wait for the next daily evaluation, confirm the email arrives, then delete the test budget.
  - Or: watch for the confirmation email AWS sends when a new SNS-backed notification subscription is created (if your budget alert routes through SNS) — subscribing to an SNS topic itself requires email confirmation, which is a lighter-weight delivery proof.
  - Whichever you use, actually see the email land in your inbox before marking this done — not "the console said saved successfully."

### 2c. CloudWatch billing alarm (secondary safeguard)
Remember: **console region must be `us-east-1`** for this step specifically — the billing metric doesn't exist anywhere else.
- [ ] Billing preferences: go to **Billing and Cost Management console → Billing Preferences**, and check **"Receive Billing Alerts"** if not already enabled — required before the CloudWatch metric populates at all.
- [ ] Switch the console region selector to **US East (N. Virginia) / us-east-1**.
- [ ] Go to **CloudWatch → Alarms → Create alarm**.
- [ ] Select metric: **Billing → Total Estimated Charge → EstimatedCharges** (currency: USD).
- [ ] Condition: Static, greater than your chosen threshold (e.g. **$130**, just above the Budget's alert level — this is a blunter secondary tripwire, not meant to fire before the Budget does).
- [ ] Configure an SNS topic for notification (create new, subscribe your email, confirm the subscription email that arrives).
- [ ] Create the alarm.

### 2d. Free-tier context (informational, not a control)
- [ ] Be aware new accounts typically get free-tier credits/allowances in the first months (the story's earlier estimate mentioned up to ~$200) — worth checking your account's actual credit balance/expiry under **Billing → Credits** so early low/no spend is read in that context, not mistaken for the budget being miscalibrated. This isn't something to configure, just something to know before you look at month-one bills.

---

## 3. Audit logging (CloudTrail)

Every AWS account already has 90 days of free management-event history with zero setup (**CloudTrail → Event history**) — confirmed current. That's not sufficient here: it's capped at 90 days and only viewable in-console, not exportable/queryable. This story wants an actual **trail** logging to S3.

- [ ] Console region: `eu-west-2`.
- [ ] Go to **CloudTrail → Trails → Create trail**.
- [ ] Trail name: `shvoy-dev-management-events` (see naming convention below).
- [ ] Storage: create a **new S3 bucket** for the logs (e.g. `shvoy-dev-cloudtrail-logs-<account-id>` — bucket names are globally unique, so suffixing with your account id avoids collisions).
- [ ] Log file SSE-KMS encryption: default (S3-managed) is fine for a pilot; skip creating a custom KMS key unless you have a reason to.
- [ ] Events: **Management events** only, Read + Write (this is what the story asks for — "the default management-events trail is sufficient"). Leave data events (S3 object-level, Lambda invocations, etc.) off — they cost more and aren't needed yet.
- [ ] Set an S3 lifecycle rule on the log bucket for retention — e.g. transition to Glacier or expire after 1 year — so logs don't accumulate indefinitely. A pilot doesn't need long retention; pick something you won't have to think about again (e.g. expire after 365 days).
- [ ] Create the trail. Confirm it shows **"Logging: On"** in the trail list.

---

## 4. Tagging & naming convention

This is the convention every resource in A2 onward follows — documented here, not re-decided per-story.

**Naming:** `shvoy-dev-<resource-purpose>` — e.g. `shvoy-dev-vpc`, `shvoy-dev-rds`, `shvoy-dev-documents` (S3), `shvoy-dev-cluster` (ECS). Lowercase, hyphen-separated, matching what's already used above for the CloudTrail bucket/trail.

**Mandatory tags on every resource that supports tagging:**

| Tag key | Value for this environment | Purpose |
|---|---|---|
| `env` | `dev` | Distinguish dev from prod once prod exists |
| `project` | `shvoy` | Cost attribution if this account ever hosts more than one project |
| `owner` | *(your name/team)* | Who to ask about a resource |

- [ ] Apply these three tags to every resource created from A2 onward (VPC, RDS, S3 buckets, ECS, ECR, etc.) — including the CloudTrail bucket and trail from Step 3, retroactively, since those were created in this story.
- [ ] If A2+ ends up using IaC, encode `env`/`project`/`owner` as a shared default tag block applied to every resource automatically, rather than repeating them per-resource — decide this when A2 starts, not now.

---

## Decisions Log

Record of the calls made in this story, for A2+ to inherit without re-litigating:

| Decision | Value | Why |
|---|---|---|
| AWS region | `eu-west-2` (London) | UK importer user base — latency and data residency outweigh the small us-east-1 cost saving |
| Human access model | IAM Identity Center (account instance), not IAM users | Current AWS guidance; free; single MFA/session-based credentials |
| Budget threshold | $120/month, alerts at 80% actual / 100% forecast | Running-stack estimate is ~$75–90/month; gives early warning without false alarms |
| CloudWatch billing alarm | $130 threshold, **console region us-east-1 only** | Secondary safeguard; billing metric is a global AWS platform exception to the eu-west-2 decision |
| CloudTrail | Management events only, to `shvoy-dev-cloudtrail-logs-<account-id>`, 365-day lifecycle | Sufficient for a pilot per the story; data events not needed yet |
| Naming convention | `shvoy-dev-<purpose>` | Cheap now, painful to retrofit |
| Mandatory tags | `env`, `project`, `owner` | Cost attribution, dev/prod distinction once prod exists |

---

## Acceptance criteria checklist (from the story)

- [ ] Root account has MFA enabled and no access keys
- [ ] A separate admin identity (Identity Center user) is used for day-to-day work, MFA-protected
- [ ] An AWS Budget exists with a monthly threshold and a working email alert (delivery confirmed, not just configured)
- [ ] A CloudWatch billing alarm is in place as a secondary safeguard (set up in us-east-1)
- [ ] CloudTrail is enabled with a trail logging to S3
- [ ] Tagging/naming convention is documented (this file) — encode as IaC default only once A2 introduces IaC
- [ ] Dev region is decided (`eu-west-2`) and documented, made deliberately rather than defaulted
- [ ] No application infrastructure (VPC, RDS, S3 app buckets, ECS) was created in this story
