# Story A8 — CI/CD deploy role (GitHub Actions → AWS via OIDC): setup checklist

The `build-push.yml` and `deploy-dev.yml` workflows authenticate to AWS with a
**short-lived OIDC token**, not stored access keys. This checklist creates the
one IAM role they assume. Region `eu-west-2`, account `038774852612`. Everything
here is a one-time AWS/GitHub-console setup; once done, the workflows run without
any secrets in the repo.

> **Why OIDC, not keys:** GitHub exchanges a per-run identity token for temporary
> AWS credentials scoped to this repo. No long-lived `AWS_ACCESS_KEY_ID` to leak,
> rotate, or find in a log. This is AWS's and GitHub's recommended pattern.

---

## 1. Ensure the GitHub OIDC provider exists in IAM (once per account)

- [ ] **IAM → Identity providers.** If `token.actions.githubusercontent.com`
  isn't already listed, add an OpenID Connect provider:
  - Provider URL: `https://token.actions.githubusercontent.com`
  - Audience: `sts.amazonaws.com`

(If another repo in this account already uses GitHub OIDC, the provider exists —
reuse it.)

---

## 2. Create the deploy role

- [ ] **IAM → Roles → Create role → Custom trust policy.** Name it
  `shvoy-dev-github-deploy`. Trust policy — scoped to **this repo**, so no other
  repo can assume it:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::038774852612:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:Shvoy-service/shvoy-backend:*"
        }
      }
    }
  ]
}
```

> Tighten `sub` later if you want (e.g. `repo:Shvoy-service/shvoy-backend:ref:refs/heads/main`
> for build-push, and an environment condition for deploy) — the wildcard is the
> simple starting point for a single-maintainer pilot.

---

## 3. Attach the permissions policy

- [ ] Attach this inline policy to `shvoy-dev-github-deploy`. It grants exactly
  what the two workflows do: push to the one ECR repo, register/roll out the ECS
  task definition, and pass the two task roles.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "EcrPushPull",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer",
        "ecr:DescribeImages"
      ],
      "Resource": "arn:aws:ecr:eu-west-2:038774852612:repository/shvoy-dev-backend"
    },
    {
      "Sid": "EcsTaskDefinition",
      "Effect": "Allow",
      "Action": [
        "ecs:DescribeTaskDefinition",
        "ecs:RegisterTaskDefinition"
      ],
      "Resource": "*"
    },
    {
      "Sid": "EcsDeploy",
      "Effect": "Allow",
      "Action": [
        "ecs:UpdateExpressGatewayService",
        "ecs:DescribeServices",
        "ecs:DescribeServiceDeployments"
      ],
      "Resource": "*"
    },
    {
      "Sid": "PassTaskRoles",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": [
        "arn:aws:iam::038774852612:role/shvoy-dev-ecs-execution-role",
        "arn:aws:iam::038774852612:role/shvoy-dev-ecs-task-role"
      ],
      "Condition": {
        "StringEquals": { "iam:PassedToService": "ecs-tasks.amazonaws.com" }
      }
    }
  ]
}
```

> **Verify before pasting:** the two Express-Mode action names
> (`ecs:UpdateExpressGatewayService`, `ecs:DescribeServiceDeployments`) are
> reconstructed from the A7 CLI verbs — Express Mode is new, so confirm the exact
> IAM action strings against current AWS docs (or, if the deploy step fails with an
> AccessDenied naming a different action, add that action here). `ecs:RegisterTaskDefinition`
> genuinely does not support resource-level scoping, hence `"Resource": "*"`.

---

## 4. Wire the role ARN into the repo

- [ ] **GitHub → repo → Settings → Secrets and variables → Actions → Variables →
  New repository variable:**
  - Name: `AWS_DEPLOY_ROLE_ARN`
  - Value: `arn:aws:iam::038774852612:role/shvoy-dev-github-deploy`

(A **variable**, not a secret — the role ARN isn't sensitive, and both workflows
read it as `${{ vars.AWS_DEPLOY_ROLE_ARN }}`.)

---

## 5. First-run validation

- [ ] **Build+push:** merge anything to `main` (or run *Build & push image*
  manually). Confirm a `sha-<short-sha>` image lands in the `shvoy-dev-backend`
  ECR repo.
- [ ] **Deploy:** run *Deploy to dev* with that tag. It registers a new task-def
  revision and calls `update-express-gateway-service`. Watch the rollout via
  `aws ecs describe-service-deployments` and
  `curl https://sh-b631174fb2e94894bb26c4718352df04.ecs.eu-west-2.on.aws/actuator/health`.
- [ ] **Migrations:** the first boot of the new revision runs any pending Flyway
  migrations against dev RDS — confirm in the `/ecs/shvoy-dev-backend` CloudWatch
  log group (there is a large batch pending, since dev hasn't been redeployed
  since it was first stood up at V10).

---

## What's deliberately still manual

- **Deploy is push-button, not automatic on merge** — a rollout runs Flyway
  against real dev RDS, so the timing stays a human decision (the decision behind
  the two-workflow split). Promoting to auto-deploy-on-merge is a one-line trigger
  change to `deploy-dev.yml` once you're comfortable.
- **No prod pipeline** — dev is the only provisioned environment. A prod promotion
  path is a later story when a prod environment exists.

## Decisions Log

| Decision | Value | Why |
|---|---|---|
| Auth | GitHub OIDC role, no stored keys | Recommended pattern; nothing long-lived to leak/rotate |
| Trust scope | `repo:Shvoy-service/shvoy-backend:*` | Simple single-repo start; tightenable to branch/environment |
| Build runner | `ubuntu-24.04-arm` (native ARM64) | Fargate runs ARM64; native build avoids emulating the Maven build under QEMU |
| Deploy trigger | Manual `workflow_dispatch` | A rollout runs Flyway against real RDS — keep deploy timing human |
| Tagging | `sha-<short-sha>` (immutable repo) | Traceable to an exact commit; pairs with A6's IMMUTABLE repo |
