# Story A7 — ECS Deploy (dev)

Deploys the container built in A6 to ECS, wired to all three real dependencies from A3/A4/A5 (RDS, S3, Cognito), producing a live, reachable dev environment with an HTTPS URL. Region: `eu-west-2`, account `038774852612`. Almost entirely CLI-driven, like A6 — Express Mode's console flow exists but the byo-task-definition path used here is CLI-only.

**Two things the story called out to get right, both confirmed:** the task role is scoped to exact ARNs (RDS secret + KMS decrypt + S3 bucket + Cognito pool — no wildcards except the account-wide-by-design `ecr:GetAuthorizationToken`), and "healthy `/health` plus a successful first Flyway migration" was treated as the core acceptance signal — except the real path is `/actuator/health`, not `/health` (see Section 5).

---

## 1. Approach: ECS Express Mode, verified before committing

Checked current AWS docs before building anything (Express Mode is new enough that stale assumptions were a real risk):
- Available in `eu-west-2`, confirmed live via `aws ecs create-express-gateway-service`.
- Supports existing VPC/subnets/security groups via `--network-configuration '{"subnets":[...],"securityGroups":[...]}'` — used A2's public subnets and `shvoy-dev-ecs-sg` rather than letting Express Mode create its own networking, since RDS's security group only trusts that specific SG ID.
- Supports a custom, separately-registered task definition via `--task-definition-arn`, which is what's used here — gives full control over the RDS/S3/Cognito environment and secrets injection that Express Mode's own `--primary-container` shorthand can't express. Confirmed constraint: `--task-definition-arn` cannot be combined with `--primary-container`/`--execution-role-arn`/`--task-role-arn`/`--cpu`/`--memory` in the same call — Express Mode derives all of those from the task definition.
- Requires the task definition's container to be named exactly `Main`, with a single TCP port mapping carrying both `containerPort` and `name`.
- When a custom security group is supplied, Express Mode still creates its own Service Security Group and Load Balancer Security Group and attaches the custom one as an *additional* ingress path — it doesn't replace Express Mode's own plumbing. Confirmed live: the task's ENI ended up with two SGs (`shvoy-dev-ecs-sg` + an auto-created one), and RDS's trust rule on the first one was unaffected.

---

## 2. IAM roles

Three roles, matching Express Mode's requirement (execution + infrastructure) plus the task role for application-level AWS access:

| Role | Trust | Permissions |
|---|---|---|
| `shvoy-dev-ecs-execution-role` | `ecs-tasks.amazonaws.com` | AWS-managed `AmazonECSTaskExecutionRolePolicy` (ECR pull + CloudWatch Logs) + inline `secretsmanager:GetSecretValue`/`kms:Decrypt` on the RDS secret — needed so the ECS agent itself can resolve the `secrets` array in the task definition, separate from the task's own runtime need for the same secret |
| `shvoy-dev-ecs-task-role` | `ecs-tasks.amazonaws.com` | Inline policy, all scoped to exact ARNs: RDS secret read + KMS decrypt (`arn:aws:kms:eu-west-2:038774852612:key/d0ea7868-c850-4afe-ab36-3a6fdce4f62f` — the AWS-managed key backing the RDS-managed secret, looked up since `KmsKeyId` was `null` on the secret), S3 get/put/delete/list on `shvoy-documents-dev-038774852612-eu-west-2-an`, and `cognito-idp:AdminCreateUser`/`AdminSetUserPassword`/`AdminDeleteUser` scoped to the pool ARN |
| `shvoy-dev-ecs-infra-role` | `ecs.amazonaws.com` | AWS-managed `AmazonECSInfrastructureRoleforExpressGatewayServices` — lets Express Mode provision the ALB, target groups, security groups, and auto-scaling on its own |

**Gotcha, self-resolved:** the very first `create-express-gateway-service` call failed with `Unable to assume the service linked role` — the account-wide `AWSServiceRoleForECS` service-linked role had just been auto-created moments earlier (by an incidental `list-clusters` call) and hadn't propagated yet. Waited briefly and retried; this matches AWS's own documented advice for freshly-created IAM roles.

---

## 3. Task definition

Registered separately (not via Express Mode's shorthand), `family: shvoy-dev-backend`, `awsvpc` network mode, Fargate, 0.5 vCPU / 1 GB:
- Container named `Main`, port 8080 with `name: "http"`.
- `SPRING_PROFILES_ACTIVE=dev`, `DB_URL` (plain env var — the RDS-managed secret is confirmed to contain only `{"username","password"}`, no host/port/dbname, so the JDBC URL has to be constructed rather than sourced from the secret), `COGNITO_USER_POOL_ID`, `COGNITO_APP_CLIENT_ID` as plain environment variables; `DB_USERNAME`/`DB_PASSWORD` sourced from the RDS secret via the `secrets` array.
- `CORS_ALLOWED_ORIGINS` set to a placeholder (`http://localhost:5173`) — the app fails to start without it (no default, by design, per the CORS cleanup story) and no real frontend domain exists yet. Needs a real value once Cloudflare Pages is wired up.
- Logs to `/ecs/shvoy-dev-backend` (created ahead of time with 30-day retention), `awslogs` driver.

**Gotcha caught before it broke anything:** the pushed image (`sha-229d80d` from A6) is **arm64-only** — its manifest index has no x86_64 variant, since it was built natively on an Apple Silicon machine. Fargate defaults to `X86_64`. Set `runtimePlatform.cpuArchitecture: ARM64` explicitly in the task definition rather than discovering this via a failed image pull. Worth a follow-up: a multi-arch `docker buildx` build would make this portable, but ARM64 Fargate is fine as-is (slightly cheaper, no functional downside).

---

## 4. Express Mode service

```bash
aws ecs create-express-gateway-service \
  --infrastructure-role-arn arn:aws:iam::038774852612:role/shvoy-dev-ecs-infra-role \
  --task-definition-arn arn:aws:ecs:eu-west-2:038774852612:task-definition/shvoy-dev-backend:2 \
  --service-name shvoy-dev-backend \
  --health-check-path /actuator/health \
  --network-configuration '{"subnets":["subnet-039ffb6a2b9dea192","subnet-0845fc2593d519037"],"securityGroups":["sg-0de50e1cb5166e4a5"]}'
```
- Cluster: `default` (auto-created, Fargate capacity provider).
- Service ARN: `arn:aws:ecs:eu-west-2:038774852612:service/default/shvoy-dev-backend`.
- **Live URL: `https://sh-b631174fb2e94894bb26c4718352df04.ecs.eu-west-2.on.aws`** — HTTPS with an Express Mode-provisioned ACM certificate, no manual cert work needed.
- Deployment strategy is canary by default (5% canary traffic, 3-minute bake, then full production shift with its own bake window) with a deployment circuit breaker and rollback alarm — not configured deliberately, just the Express Mode default, and it worked as intended (see Section 6).

---

## 5. The real health-check bug: `/health` vs `/actuator/health`

The story's own text assumed `/health`. `application.yml` has `management.endpoints.web.exposure.include: health,info` with no `base-path` override, so the actual Spring Boot Actuator path is `/actuator/health` — confirmed by reading the config before wiring anything, not by guessing. Used `/actuator/health` as the `--health-check-path` from the start.

That wasn't the only issue, though. The **first live deployment (task-definition revision 1) had every target group host marked unhealthy with a real HTTP 401**, not a missing-path 404. Root cause: `SecurityConfig`'s `defaultSecurityFilterChain` (the `!local & !test` chain, i.e. the one dev/prod actually run) had `.anyRequest().authenticated()` with no exemption for the actuator endpoint — so the ALB's unauthenticated health probe was correctly rejected by Spring Security. This code path had never been exercised end-to-end before (local/test both run on the fully-permissive chain), so it was a genuine first-exposure bug, not a regression.

Fixed in `SecurityConfig.java` (commit `04935ab`): added an `ACTUATOR_ENDPOINTS` exemption (`/actuator/health`, `/actuator/health/**`) to the `permitAll()` list, alongside the existing tenant-exempt and API-docs exemptions. Rebuilt, pushed as `sha-04935ab`, registered as task-definition revision 2, rolled out via `update-express-gateway-service` (note: `--infrastructure-role-arn` is immutable after creation and must be omitted from update calls, unlike create).

---

## 6. Deployment behaviour observed (not a bug, just worth recording)

The first canary attempts on revision 2 churned through three replacement tasks before one stuck — each fresh task takes ~35-40s to fully start (JVM boot, Hibernate, Flyway), and the ALB's health check (30s interval, unhealthy-threshold 2, healthy-threshold 5) flips a target to `unhealthy` after just the first two pre-startup connection-refused checks; recovering to `healthy` then needs 5 *consecutive* successes (~2.5 min) with no interruption. The first few replacement tasks didn't make it through that window cleanly before Express Mode's deployment lifecycle replaced them; the next one did. Confirmed via `aws ecs describe-service-deployments` lifecycle stages (`PRE_SCALE_UP` → `SCALE_UP` → `PRODUCTION_TRAFFIC_SHIFT` → `BAKE_TIME` → `CLEAN_UP`) and target-group health directly — not a configuration problem, just cold-start timing against fairly aggressive default thresholds. No action taken; noted here in case it recurs on a future deploy and looks alarming.

---

## 7. Verification

- **Health**: `curl https://sh-b631174fb2e94894bb26c4718352df04.ecs.eu-west-2.on.aws/actuator/health` → `HTTP 200 {"status":"UP","groups":["liveness","readiness"]}`.
- **Flyway**: confirmed via CloudWatch Logs (`/ecs/shvoy-dev-backend`) — all 8 pending migrations applied cleanly against real RDS on first boot (`Successfully applied 8 migrations to schema "public", now at version v10`); the second task (post health-check fix) correctly found the schema already at v10 and skipped migration, confirming Flyway's idempotency held across the redeploy.
- **RDS connectivity**: HikariCP connected on first attempt using `DB_URL` (plain env var) + `DB_USERNAME`/`DB_PASSWORD` (from the RDS-managed secret) — no connectivity issues, confirming the task's attached `shvoy-dev-ecs-sg` is correctly trusted by `shvoy-dev-rds-sg`.
- **Cognito reachability**: proven for real, not just IAM-policy-scoped-and-hoped. Ran a full registration → activation cycle against the live URL: `POST /api/onboarding/register` (201, DB write), pulled the verification token from CloudWatch Logs, `POST /api/onboarding/activate` — first attempt hit a real `InvalidPasswordException` from Cognito's actual password policy (proves the task authenticated to `AdminCreateUser` successfully), retry with a compliant password hit `UsernameExistsException` (the first attempt's `AdminCreateUser` had already succeeded before `AdminSetUserPassword` failed — see gotcha below), cleaned up via `AdminDeleteUser`, then a clean retry returned `200 {"status":"ACTIVE", "cognito_sub" populated}`.
- **JWT resource-server path**: exercised for the first time ever against a live pool (previously untestable — no dev pool existed until A5). Used `admin-initiate-auth` with the newly-activated user's credentials to get a real Cognito access token, then called the `ADMIN`-protected `GET /api/onboarding/company/{companyId}/users` through the live ALB with it as a Bearer token → `200`, correct single-user result. Confirms `CognitoJwtDecoder` (JWKS validation + the custom `client_id` claim check), `CognitoJwtAuthenticationConverter` (profile resolution by `cognito_sub`, role → `ROLE_ADMIN` authority), and `TenantContextFilter`'s JWT-claim tenant resolution all work correctly end-to-end.
- **S3 reachability**: **not directly exercised** — there's no application endpoint that calls S3 yet (only `S3Config`'s bean wiring exists, confirmed back in A4; no document upload/download feature has been built). The task role's S3 permissions are scoped correctly by the same pattern already proven correct for RDS and Cognito, but this is a genuine gap, not a false checkmark — worth closing with a real functional test once a document-handling feature exists.

**Gotcha, real edge case in the Cognito integration story's error handling, not an A7 infra issue:** `RegistrationService.activate()` calls `identityProvider.createConfirmedUser()` (which does `AdminCreateUser` then `AdminSetUserPassword`) before touching the DB. If `AdminCreateUser` succeeds but `AdminSetUserPassword` then fails (e.g. password policy rejection), there's no compensating `AdminDeleteUser` for that specific failure — the plan's existing compensation logic only covers the "DB update affected 0 rows" race, not a partial failure inside `createConfirmedUser` itself. Hit this live: a weak-password activation attempt left an orphaned, unconfirmed Cognito user that then blocked all retries for that email with `UsernameExistsException` until manually deleted. Documented here rather than fixed, since it's Cognito-integration-story scope, not A7 deploy scope — worth a follow-up story.

**Test data left in place**: the verification run created a real company ("A7 Verify Co") and an ACTIVE user (`a7-verify-<timestamp>@example.com`) in the actual dev RDS database and Cognito pool. Not cleaned up — RDS isn't reachable from outside the VPC (per A5's finding) and there's no company-deletion endpoint, so cleanup would need either a bastion/VPN or a future admin capability. Harmless (clearly test-named, isolated to its own tenant), but worth knowing it's there.

---

## Decisions Log

| Decision | Value | Why |
|---|---|---|
| Deployment mechanism | ECS Express Mode, verified against live docs first | Story's own preference; confirmed available and VPC-compatible in `eu-west-2` before committing |
| Task definition | Custom, registered separately, passed via `--task-definition-arn` | Full control over RDS/S3/Cognito secrets and env injection, which Express Mode's own shorthand can't express |
| Networking | Existing A2 public subnets + `shvoy-dev-ecs-sg`, not Express Mode's auto-VPC | RDS's security group only trusts that specific SG ID by name |
| CPU architecture | `ARM64` (not the Fargate default `X86_64`) | The A6 image is arm64-only (built on Apple Silicon); caught via manifest inspection before the first deploy attempt, not via a failed pull |
| Health check path | `/actuator/health` | Verified against `application.yml`'s actual actuator config rather than trusting the story's stated `/health` |
| `DB_URL` | Plain task-definition env var, not secret-sourced | RDS-managed secret confirmed to contain only `{"username","password"}`, no host/port/dbname |
| `CORS_ALLOWED_ORIGINS` | Placeholder `http://localhost:5173` | No default exists by design (fails startup rather than allowing an unset origin); real frontend domain not yet known |
| Security chain fix | Added `/actuator/health` to the `permitAll()` exemptions on the dev/prod chain | First-ever exercise of that filter chain surfaced a real 401-on-health-check bug; committed separately (`04935ab`) from the rest of A7's uncommitted infra work |
| S3 functional verification | Deferred, IAM policy only | No application code exists yet that calls S3; documented as a real gap rather than a false pass |
| Test data cleanup | Left in place | RDS unreachable from outside the VPC; no deletion endpoint exists |

---

## Acceptance criteria checklist (from the story)

- [x] Container deployed to ECS (Express Mode, Fargate, ARM64) with a live HTTPS URL
- [x] Task role scoped to exact ARNs for RDS secret + KMS decrypt + S3 bucket + Cognito admin actions — no wildcards except the unavoidable account-wide `ecr:GetAuthorizationToken`
- [x] `/actuator/health` returns healthy (verified as the real path, not the story's assumed `/health`)
- [x] First Flyway migration succeeds against real RDS (8 migrations applied cleanly, confirmed via logs)
- [x] RDS connectivity confirmed (HikariCP connects using the SG-trust chain from A2/A3)
- [x] Cognito reachability confirmed for real (full register → activate → JWT-protected-endpoint cycle against the live URL, not just IAM policy inspection)
- [ ] S3 reachability — IAM policy correctly scoped, but not functionally exercised (no app feature calls S3 yet); tracked as a follow-up
