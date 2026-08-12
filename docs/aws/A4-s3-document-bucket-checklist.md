# Story A4 — S3 Document Bucket (Dev): Console Checklist

Console-driven, same as A1–A3. Region: `eu-west-2`. Doesn't depend on A2/A3 — S3 isn't VPC-bound, this could be done any time after A1.

**Read this before starting — the story's premise doesn't match the current repo:**

The story says to "confirm that same code, pointed at real S3 via the `dev` profile config... performs an upload and download successfully," on the assumption that document-handling service code already exists from Feature 1. I checked the actual codebase before writing this, and **that code doesn't exist yet** — no upload/download service, no document entity, nothing wired to a controller. What *does* exist is `S3Config.java`: two `S3Client` beans, one for `local` (pointed at LocalStack) and one for every other profile (pointed at real AWS), which is genuinely the exact abstraction boundary the story cares about — it's just one level lower than "application code," since there's no application code built on top of it yet.

So Step 4 below verifies that specific bean-construction difference directly, rather than a feature that doesn't exist. When real document-handling code is eventually written (presumably a later feature), it'll sit on top of this same `S3Client` bean and inherit the same local/dev split for free — that's still the point being proven here, just at the layer that actually exists today.

**Confirmed before writing this:** as of 2026, new S3 buckets have **Block Public Access** and **SSE-S3 default encryption** on automatically — several steps that used to require explicit configuration are now just "confirm it's already on," not "turn it on."

---

## 1. The bucket

- [ ] **S3 console → Create bucket**.
- [ ] Bucket name: try `shvoy-documents-dev` first — this matches what's **already hardcoded** in `application-dev.yml` (`aws.s3.documents-bucket: shvoy-documents-dev`), so if it's available, no code change is needed at all.
  - If that name is taken (S3 names are globally unique), use **Account regional namespace** (the feature from A1/A3 — guaranteed-unique, auto-suffixed with your account ID and region) instead of hunting for a manually-unique global name.
  - **If you end up with an account-regional-namespace name, you must update `aws.s3.documents-bucket` in `application-dev.yml` to match** — flag this back to me and I'll make that one-line change; don't leave the app pointed at a bucket name that doesn't exist.
- [ ] Region: `eu-west-2` (should already be selected, matching your console region).
- [ ] Object Ownership: leave at the default (**ACLs disabled** — this is now standard and matches "no public access" intent).
- [ ] **Block Public Access settings for this bucket:** confirm all four sub-settings show as **enabled** — this should already be the case by default, but a story acceptance criterion is explicit about this, so actually look rather than assume.
- [ ] Bucket Versioning: leave **disabled** — explicitly out of scope per the story (a reasonable later addition, not required now).
- [ ] **Default encryption:** confirm **SSE-S3** shows as the encryption type (should be pre-selected/default — no KMS key needed, keeping this simple per the story's own recommendation).
- [ ] Tags: `env=dev`, `project=shvoy`, `owner=<you>`.
- [ ] Create bucket.

### 1a. Confirm the account-level block too
Per-bucket Block Public Access is one thing; there's also a separate account-wide setting.
- [ ] **S3 console → Block Public Access settings for this account** (left nav) → confirm all four are enabled account-wide. If any account-level setting is somehow off, per-bucket settings can't fully protect you — this is the belt-and-braces check the acceptance criteria specifically calls out.

---

## 2. Lifecycle rule — transition to Infrequent Access, never auto-delete

- [ ] Open the bucket → **Management** tab → **Lifecycle rules** → **Create lifecycle rule**.
- [ ] Rule name: `shvoy-dev-documents-transition-to-ia`.
- [ ] Scope: apply to **all objects in the bucket** (no prefix/tag filter needed for a pilot).
- [ ] Lifecycle rule actions: check **"Transition current versions of objects between storage classes"**.
- [ ] Add a transition: **Standard-IA (Infrequent Access)**, **90 days** after object creation.
- [ ] **Do not** check any expiration action ("Expire current versions of objects", "Permanently delete..." etc.) — these are business records (invoices, BLs, price files); the story is explicit that nothing here should ever auto-delete, only change storage class.
- [ ] Create rule.

---

## 3. IAM permissions for A7 (documented here, not created yet — no ECS task role exists until A7)

The ECS task role A7 creates will need this policy, scoped to this bucket only — never `s3:*` on `Resource: *`. Recorded here so A7 can copy it directly rather than re-deriving it.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ObjectLevelAccess",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::shvoy-documents-dev-038774852612-eu-west-2-an/*"
    },
    {
      "Sid": "BucketLevelListing",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::shvoy-documents-dev-038774852612-eu-west-2-an"
    }
  ]
}
```

Note the two-statement shape isn't arbitrary: `GetObject`/`PutObject`/`DeleteObject` are object-level actions and need the `/*` object ARN pattern; `ListBucket` is a bucket-level action and needs the bucket's own ARN *without* `/*` — a single statement mixing both would silently fail for whichever one doesn't match its required ARN shape. (Update the bucket name in this policy if you ended up with an account-regional-namespace name in Step 1.)

- [ ] No SSE-KMS permissions needed (`kms:Decrypt`/`kms:GenerateDataKey`) — moot, since Step 1 used SSE-S3.

---

## 4. Verify the local/dev abstraction actually holds

This is the part the story cares about most — see the note at the top of this file for why it's scoped to the `S3Client` bean rather than nonexistent application code.

- [x] AWS CLI installed (Homebrew) and `aws configure sso` run against the Identity Center user from A1, profile `shvoy-dev`. No long-lived IAM access key created.
- [x] `S3DevBucketManualVerification` run with `AWS_PROFILE=shvoy-dev` — **passed**: uploaded, downloaded, and cleaned up a real object against `shvoy-documents-dev-038774852612-eu-west-2-an` using the exact `S3Client` construction from `S3Config`'s real (`!local`) bean. Confirms the local/dev abstraction holds — only the Spring profile differs, no code.
- [x] One real gap found and fixed along the way: the AWS SDK for Java can't resolve SSO-based profile credentials without `software.amazon.awssdk:sso` and `software.amazon.awssdk:ssooidc` on the classpath — neither is pulled in by the `s3` module alone. Added both to `pom.xml` as **test-scope only** (the deployed app never uses SSO — ECS tasks authenticate via their task IAM role in A7).

---

## Decisions Log

| Decision | Value | Why |
|---|---|---|
| Bucket name | `shvoy-documents-dev-038774852612-eu-west-2-an` (plain name was taken) | Account-regional-namespace fallback; `application-dev.yml` updated to match |
| Encryption | SSE-S3 (default) | Sensible default per the story; avoids KMS cost/complexity and the extra IAM permissions it would need |
| Public access | Blocked at bucket AND account level | Commercial documents (invoices, BLs) must never be publicly readable |
| Versioning | Disabled | Explicitly out of scope for the dev pilot; reasonable later addition |
| Lifecycle | Standard-IA after 90 days; no expiration | Matches original foundation planning; documents are business records, never auto-deleted |
| A7 IAM policy | Scoped to this bucket's ARN only (documented above) | Never `s3:*` / `Resource: *` |
| Verification approach | Direct test of `S3Config`'s real bean construction, not "existing document code" | That code doesn't exist yet — see note at top |

---

## Acceptance criteria checklist (from the story)

- [x] A private, encrypted S3 bucket exists in `eu-west-2` with A1 tags, named per convention (`shvoy-documents-dev-038774852612-eu-west-2-an`)
- [x] All public access blocked at bucket **and** account level (Step 1a)
- [x] Server-side encryption enabled (SSE-S3, confirmed not just assumed)
- [x] Lifecycle rule transitions to IA after 90 days; no expiration/deletion rule exists
- [x] Required S3 permissions documented for the A7 task role (Step 3), scoped to this bucket only
- [x] The real abstraction point (`S3Config`'s bean split) verified against the real bucket (Step 4) — adjusted from "existing document-handling code" per the note at the top
- [x] No application feature logic changed — only per-profile configuration differs (trivially true here, since there's no feature logic yet to change)
