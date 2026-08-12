# Story A3 — RDS PostgreSQL (Dev): Console Checklist

Console-driven, same as A1/A2. Region: `eu-west-2`. Depends on A2 being done — you need `shvoy-dev-vpc`, its two public subnets, and `shvoy-dev-rds-sg` already existing.

**Confirmed before writing this:** local Docker Compose runs `postgres:16` (`docker-compose.yml`) with no minor version pinned — so RDS gets **PostgreSQL 16**, latest available minor (per your own call: major-version match matters, minor doesn't). Also confirmed current: RDS's **"Manage master credentials in Secrets Manager"** is a live, first-class creation-time option (not something to hand-roll) — this checklist uses it.

**One constraint worth knowing before you start:** when RDS manages the secret itself, AWS generates the secret's name automatically (pattern `rds!db-<uuid>`) — there's no field to type a custom `shvoy-dev-...` name for it, and renaming a live RDS-managed secret afterward isn't something I could confirm is safe without risking breaking RDS's link to it. So this checklist doesn't fight that: the secret keeps its AWS-generated name, gets the `env`/`project`/`owner` tags added manually afterward (Secrets Manager supports tagging even though the creation wizard doesn't expose it), and its **ARN** — not a predictable name — is what gets recorded here for A7 to reference. That's actually how services normally consume secrets anyway (by ARN, via IAM permissions), so this isn't a compromise, just worth knowing so the missing custom name doesn't look like something went wrong.

---

## 1. DB subnet group

RDS needs its own "DB subnet group" object — a named grouping of which VPC subnets it's allowed to use — separate from the plain VPC subnets A2 created.

- [ ] **RDS console → Subnet groups → Create DB subnet group**.
- [ ] Name: `shvoy-dev-rds-subnet-group`. Description: "Subnets available to the dev RDS instance."
- [ ] VPC: `shvoy-dev-vpc`.
- [ ] Availability Zones: select both `eu-west-2a` and `eu-west-2b`.
- [ ] Subnets: select `shvoy-dev-public-eu-west-2a` and `shvoy-dev-public-eu-west-2b` (the same two from A2 — there's no separate private tier, per A2's decision).
- [ ] Tags: `env=dev`, `project=shvoy`, `owner=<you>`.
- [ ] Create.

---

## 2. Create the instance

**RDS console → Databases → Create database.**

### Engine
- [ ] Engine type: **PostgreSQL**.
- [ ] Engine version: the latest available **16.x** minor version in the dropdown (matches local's major version 16; don't chase a specific minor).
- [ ] Templates: choose **Dev/Test** as your starting point — it pre-selects sensible dev defaults, but verify every field below explicitly rather than trusting it blindly.

### Instance identifier & credentials
- [ ] DB instance identifier: `shvoy-dev-rds` (matches the A1 naming convention).
- [ ] Master username: `shvoyadmin` (avoids relying on the generic `postgres` default, purely for clarity when you're later looking at Secrets Manager entries — not a hard requirement).
- [ ] Credentials management: select **"Manage master credentials in AWS Secrets Manager"**. Leave the default encryption key (AWS managed key) unless you have a specific reason for a customer-managed KMS key.

### Instance configuration
- [ ] DB instance class: **Burstable classes (includes t classes)** → `db.t4g.small`.

### Storage
- [ ] Storage type: **gp3**.
- [ ] Allocated storage: **20 GB** (ample for a pilot dev database).
- [ ] Storage autoscaling: leave **disabled**, or enable with a small max (e.g. 50 GB) if you want a safety margin — either is fine per the story; a fixed allocation is the simpler default.

### Connectivity
- [ ] Compute resource: **Don't connect to an EC2 compute resource** (you're wiring this to ECS in A7, not EC2, and not yet).
- [ ] VPC: `shvoy-dev-vpc`.
- [ ] DB subnet group: `shvoy-dev-rds-subnet-group` (from Step 1).
- [ ] **Public access: No.** This is the acceptance-criteria-critical setting — confirm it's set to No, not just left on a default.
- [ ] VPC security group: **Choose existing** → remove the default/pre-selected one if the wizard added one → select **only** `shvoy-dev-rds-sg` (the one from A2 that already only trusts the ECS security group on 5432). Don't let it also attach the VPC's default security group.
- [ ] Availability Zone: no preference (let RDS pick, since this is Single-AZ anyway).
- [ ] Database port: `5432` (default — matches what `shvoy-dev-rds-sg` already expects).

### Database authentication
- [ ] Leave as **Password authentication** (the Secrets-Manager-managed option above already covers this — no need for IAM database authentication for a pilot).

### Monitoring
- [ ] Leave **Performance Insights** and **Enhanced monitoring** at their Dev/Test template defaults (off, or minimal) — explicitly out of scope for this story.

### Additional configuration (expand this section — it's collapsed by default and easy to miss)
- [ ] **Initial database name:** `shvoy` (matches local's `POSTGRES_DB: shvoy` — this is the database Flyway will connect to and populate from A7 onward; without setting this, RDS creates the instance with no default database and the app would have nowhere to connect).
- [ ] **DB parameter group / option group:** leave at defaults.
- [ ] **Backup:**
  - Enable automated backups: **checked**.
  - Backup retention period: **7 days**.
  - Backup window: leave default (no specific time-of-day requirement for a pilot).
- [ ] **Encryption:** leave enabled at the default (AWS managed key) — no reason to disable storage encryption even for dev.
- [ ] **Maintenance:** leave auto minor version upgrade at its default; maintenance window default is fine.
- [ ] **Deletion protection:** worth turning **on** even for dev — cheap insurance against a fat-fingered console delete; doesn't cost anything and can be turned off later if you deliberately want to tear the instance down.
- [ ] Tags: `env=dev`, `project=shvoy`, `owner=<you>`.

- [ ] **Create database.** This takes several minutes — the instance status will show "Creating" then "Available."

---

## 3. Tag and record the auto-created secret

- [ ] Once the instance shows **Available**, open it → **Configuration** tab → find the **"Master credentials ARN"** (or similar) field — this links directly to the Secrets-Manager-managed secret.
- [ ] Follow that link (or go to **Secrets Manager console** and look for a secret named `rds!db-<some-uuid>` associated with `shvoy-dev-rds`).
- [ ] On the secret's page → **Tags** → add `env=dev`, `project=shvoy`, `owner=<you>` (the RDS creation wizard's tags don't automatically propagate to this secret — it needs tagging separately).
- [ ] Copy the secret's **ARN** and record it in the Decisions Log below — this is what A7 will reference (via the ECS task execution role's IAM permissions and the task definition's `secrets` block), not a name.
  arn:aws:secretsmanager:eu-west-2:038774852612:secret:rds!db-d26f3e25-7bae-46f1-b460-f8258d72e41e-T84a5B
---

## 4. Connectivity verification

You can't fully prove the ECS→RDS path until A7 actually deploys a task (there's no task yet to test from). What you *can* confirm now:

- [ ] **Security group rule check:** `shvoy-dev-rds-sg` inbound should show exactly one rule — PostgreSQL/5432 from `shvoy-dev-ecs-sg` (confirm this is still exactly as A2 left it; nothing here should have added a broader rule).
- [x] **Public unreachability check:** confirmed — `nc -zv -w 10 shvoy-dev-rds.cl08imme8ei6.eu-west-2.rds.amazonaws.com 5432` from outside AWS resulted in `Operation timed out` (after ~75s). Hostname resolved fine; nothing answered on 5432. Exactly the expected result for "Public access: No" plus a correctly-scoped security group.

---

## 5. Flyway note — do not touch the schema

- [ ] **Do not** connect to this instance and run any migrations, `CREATE TABLE`, or seed data manually. The database is meant to stay empty.
- [ ] Flyway (already part of the app, from Feature 1) runs automatically on application startup and will create every table itself the first time the deployed container (A7) connects. Running anything manually now risks conflicting with what Flyway expects to create later.
- [ ] The one thing that does need to already exist is the **database itself** (`shvoy`, set in Step 2's Additional configuration) — Flyway populates schema/tables within it, but doesn't create the database.

---

## Decisions Log

| Decision | Value | Why |
|---|---|---|
| Postgres version | 16.x (latest minor) | Matches local Docker Compose's `postgres:16` major version — minor mismatch is fine per your own call |
| Instance class | `db.t4g.small` | Pilot-appropriate size from the cost analysis |
| Multi-AZ | No — Single-AZ | Deliberate dev/pilot choice; HA deferred to a later infrastructure feature |
| Storage | gp3, 20 GB, autoscaling off | Ample for pilot dev data volume |
| Backups | Automated, 7-day retention | Cheap insurance against a bad migration, even in dev |
| Public access | **No** | Core acceptance criterion — DB only reachable from `shvoy-dev-ecs-sg` |
| Credentials | RDS-managed, in Secrets Manager | Current AWS mechanism; password never handled in plain text, AWS rotates it |
| Secret name | AWS-generated (`rds!db-...`), not custom | RDS-managed secrets can't be named at creation; A7 references it by **ARN** (recorded below), not by name |
| Secret ARN | *(fill in after Step 3)* | — |
| Initial database name | `shvoy` | Matches local `POSTGRES_DB`, gives Flyway somewhere to run |
| Master username | `shvoyadmin` | Clarity only, not a functional requirement |
| Deletion protection | On | Cheap insurance against an accidental console delete |

---

## Acceptance criteria checklist (from the story)

- [ ] A `db.t4g.small` Single-AZ PostgreSQL instance exists in `eu-west-2`, in `shvoy-dev-vpc`, with A1 tags applied
- [ ] Postgres major version matches local (16)
- [ ] Attached to `shvoy-dev-rds-sg`; **Public access: No**
- [ ] Master credentials in Secrets Manager (RDS-managed), never in plain config; ARN recorded above for A7
- [ ] gp3 storage allocated; automated daily backups, 7-day retention
- [ ] Security group permits access only from `shvoy-dev-ecs-sg` on 5432 (verified Step 4)
- [ ] A direct public connection attempt times out rather than succeeding (verified Step 4)
- [ ] No migrations run manually — instance left empty (aside from the `shvoy` database existing) for Flyway
