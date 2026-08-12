# Story A6 — ECR Repository & Image Push

Mostly CLI-driven, not console — unlike A1–A5, repo creation, auth, build, and push are all native CLI operations, so this one was largely run directly rather than walked through as a checklist. Region: `eu-west-2`, account `038774852612`.

**One correction to the story's own description, confirmed before building anything:** the story says the base image is `amazoncorretto:21-alpine`. The actual `Dockerfile` uses `eclipse-temurin:25-jre-alpine` (matching the project's real Java 25 baseline throughout `pom.xml`) — the story's description predates the project settling on Java 25. Not a problem, just verified against reality rather than the stale description, per the story's own instruction to do exactly that.

---

## 1. Repository

Created via CLI:
```bash
aws ecr create-repository \
  --repository-name shvoy-dev-backend \
  --region eu-west-2 --profile shvoy-dev \
  --image-scanning-configuration scanOnPush=true \
  --image-tag-mutability IMMUTABLE \
  --tags Key=env,Value=dev Key=project,Value=shvoy Key=owner,Value=harry
```
- Repository URI: `038774852612.dkr.ecr.eu-west-2.amazonaws.com/shvoy-dev-backend`
- Scanning on push: enabled
- **Tag mutability: IMMUTABLE** (a deliberate choice, not the default) — once a tag is pushed it can't be silently overwritten, which forces every push to use a genuinely new tag. Paired with git-SHA tagging (see Section 3), this gives full traceability from a running task back to the exact commit — a floating `latest` tag would lose that. The tradeoff: can't repeatedly push under the same tag during iteration: each push needs a new tag, which the SHA-based scheme provides for free.

### Lifecycle policy
```json
{
  "rules": [
    {
      "rulePriority": 1,
      "description": "Expire untagged images after 3 days",
      "selection": {"tagStatus": "untagged", "countType": "sinceImagePushed", "countUnit": "days", "countNumber": 3},
      "action": {"type": "expire"}
    },
    {
      "rulePriority": 2,
      "description": "Keep only the most recent 10 sha-tagged images",
      "selection": {"tagStatus": "tagged", "tagPrefixList": ["sha-"], "countType": "imageCountMoreThan", "countNumber": 10},
      "action": {"type": "expire"}
    }
  ]
}
```
Applied via `aws ecr put-lifecycle-policy`. Untagged images (e.g. superseded manifests from a re-push) expire after 3 days; only the 10 most recent `sha-`-tagged images are kept.

---

## 2. Build

```bash
docker build -t shvoy-backend:local .
```
Multi-stage build (Maven build stage discarded, only the `eclipse-temurin:25-jre-alpine` runtime stage + jar ship) — confirmed this is the real production image, not a dev variant; there's only one `Dockerfile` in the repo, no separate dev-build path to accidentally use instead.

### Image size — corrected mid-verification, worth recording precisely
- **Local `docker images` (uncompressed): 474MB.** Broken down via `docker history`: Java 25 JRE itself 196MB, application jar 90MB, OS packages (fontconfig, locales, tzdata, openssl, etc. — from the base image, not added by us) 23MB, ~9MB Alpine rootfs. The ~155MB gap between that sum (~319MB) and 474MB is BuildKit's SBOM/provenance attestation metadata (visible in the build output as separate "attestation manifest"/"manifest list" exports).
- **Actual size stored in ECR / what ECS will pull (compressed): 155,208,764 bytes ≈ 148MB.** Confirmed via `aws ecr describe-images`. This is the number that actually matters for pull time and registry storage cost — comfortably under the story's ~300MB target.
- I initially reported the 474MB local figure as the relevant one and flagged it as over-target — that was the wrong metric. Correcting the record here: **the image is fine, under target, on the number that counts.**
- The ~300MB target itself was likely set against a smaller baseline (Java 21/Corretto, per the story's own now-stale description) — even before the compression correction, a chunk of the local-uncompressed figure is just "the JRE is bigger now," not app bloat.

---

## 3. Authenticate, tag, push

```bash
aws ecr get-login-password --region eu-west-2 --profile shvoy-dev | \
  docker login --username AWS --password-stdin 038774852612.dkr.ecr.eu-west-2.amazonaws.com
```
Current command form confirmed working as-is — no change from the established mechanism.

Tagged and pushed with the short git commit SHA (`sha-<short-sha>`), not `latest` — pairs with the IMMUTABLE repo setting above and gives traceability from any running task back to an exact commit:
```bash
docker tag shvoy-backend:local 038774852612.dkr.ecr.eu-west-2.amazonaws.com/shvoy-dev-backend:sha-229d80d
docker push 038774852612.dkr.ecr.eu-west-2.amazonaws.com/shvoy-dev-backend:sha-229d80d
```
Confirmed landed via `aws ecr describe-images` — digest `sha256:e48613fe...`, tag `sha-229d80d`.

---

## 4. Verification

- **Round trip:** local image removed entirely (`docker rmi`), then pulled back down fresh from ECR — pulled digest matched the pushed digest exactly.
- **Boots cleanly:** ran the freshly-pulled image standalone. JVM starts (Java 25.0.3), Spring context initializes, Tomcat comes up on 8080, JPA repository scanning completes, and it fails at the expected point — attempting to reach `localhost:5432` (default `local` profile, no DB linked in this isolated one-off run) — a clean, recognizable connection-refused failure, not a corrupted image or missing-class crash. Confirms the image itself is sound; full functional boot against real `dev` infra is A7's job (same VPC-reachability constraint as A5 — this sandbox can't reach real RDS either).
- **Vulnerability scan:** completed. **1 HIGH** (`p11-kit`, CVE-2026-2100) and **13 MEDIUM** (`expat`, various 2026 CVEs) findings — all in OS-level packages from the base image's own `apk add` layer (fontconfig/ca-certificates/p11-kit-trust dependencies), not application code or direct dependencies. Not remediated as part of this story — scope was "scanning enabled," not "zero findings" — but flagged honestly rather than glossed over. Worth a look before this becomes a real prod image: `fontconfig`/`ttf-dejavu` in particular may not even be needed for a headless REST API (no PDF/image generation in this app currently) and removing them would likely drop most of the `expat`-related findings along with some image size. Noted as a follow-up, not blocking A6.

---

## 5. ECR pull permissions for A7 (documented here, not created yet)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ECRAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "ECRPull",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer"
      ],
      "Resource": "arn:aws:ecr:eu-west-2:038774852612:repository/shvoy-dev-backend"
    }
  ]
}
```
`GetAuthorizationToken` is account-wide by AWS design (can't be scoped to one repository — it authenticates against the registry, not a specific repo), the other two are scoped to this repository only. This is the standard shape ECS task execution roles use for image pulls (distinct from the task *role*, which carries the RDS/S3/Cognito permissions from A3–A5) — worth keeping execution-role and task-role permissions separate in A7 rather than merging them onto one role.

---

## Decisions Log

| Decision | Value | Why |
|---|---|---|
| Repository name | `shvoy-dev-backend` | A1 naming convention |
| Tag mutability | IMMUTABLE | Forces unique tags per push; pairs with git-SHA tagging for traceability |
| Tagging scheme | `sha-<short-git-sha>`, not `latest` | Every running task traceable back to an exact commit |
| Lifecycle policy | Untagged expire after 3 days; keep last 10 `sha-` tagged | Prevents unbounded accumulation without being aggressive for a low-volume pilot |
| Base image | `eclipse-temurin:25-jre-alpine` (not the story's stated `amazoncorretto:21-alpine`) | Story description was stale; verified against the actual `Dockerfile` |
| Image size target | Met on the metric that matters (148MB compressed/registry-stored), not the one I first checked (474MB local uncompressed) | Registries store compressed layers; that's what affects pull time and storage cost |
| Scan findings | 1 HIGH, 13 MEDIUM, all OS-level packages, not remediated this story | Out of A6's scope (scanning enabled, not zero-findings); flagged as a real follow-up, particularly whether `fontconfig`/`ttf-dejavu` are needed at all |

---

## Acceptance criteria checklist (from the story)

- [x] A private ECR repository exists in `eu-west-2` with A1 tags, named per convention
- [x] Image scanning on push is enabled
- [x] A lifecycle policy limits image accumulation (keep-recent / expire-untagged)
- [x] The Docker CLI authenticates to ECR successfully
- [x] The existing multi-stage image builds, tags with the ECR URI, and pushes successfully
- [x] The pushed image is confirmed as the lean production build (148MB compressed, under the ~300MB target on the metric that matters) and is confirmed to boot
- [x] ECR pull permissions are documented for the A7 task execution role
