# Story A2 — Networking Baseline (Dev): Console Checklist

Console-driven, same as A1, for consistency. Region: `eu-west-2` throughout (per A1's decision) — double-check the region selector before every step, given how easily it resets (bit us during A1).

**No RDS, ECS, or ALB is created in this story.** Only the VPC, subnets, routing, and three empty security groups they'll later attach to.

---

## 0. The design this checklist builds

One VPC, **two public subnets only** — no private subnet tier, no NAT Gateway. Both the ECS tasks (A7) and the RDS instance (A3) will live in these same public subnets. That's not a compromise on isolation: reachability is enforced entirely by the security-group chain (Step 4 below), not by subnet/routing structure. A resource in a "public" subnet is only actually reachable from the internet if it *both* has a public IP *and* its security group allows the traffic — RDS's security group will only ever allow traffic from the ECS security group, so it stays unreachable from the internet regardless of which subnet it sits in. (A3 should additionally set RDS's own "Publicly accessible" flag to **No** as a second, independent layer — belt-and-braces, not load-bearing on its own.)

**Why no NAT:** ~$32/month plus data processing charges, for a pilot that doesn't need the extra isolation private subnets would buy. Documented explicitly here per the story's requirement — this is a deliberate trade-off, not a default. If SHVOY's traffic/compliance needs change later, revisiting this (NAT or VPC endpoints + private subnets) is straightforward — it doesn't require re-doing anything else in this story.

**Gotcha worth knowing before you start:** since there's no NAT, every ECS task and the ALB need a **public IP** to reach the internet at all (pull container images, reach ECR/S3/Secrets Manager/Cognito) — an IGW route alone isn't enough without one. This gets configured on the subnets now (auto-assign public IP) and must also be turned on for the ECS service itself when A7 creates it. Flagging now so it isn't a mysterious "task can't pull its image" failure three stories from now.

---

## 1. VPC

- [ ] **VPC console → Your VPCs → Create VPC**.
- [ ] Choose **VPC only** (not the "VPC and more" wizard — for this story, doing subnets/routing as separate explicit steps below makes it easier to verify each piece, given a few AWS console defaults have already surprised us in A1).
- [ ] Name tag: `shvoy-dev-vpc`.
- [ ] IPv4 CIDR block: `10.0.0.0/16` (a /16 — 65,536 addresses, plenty of headroom for a pilot that will never need anywhere close to that many).
- [ ] IPv6: not needed, leave off.
- [ ] Tags: add `env=dev`, `project=shvoy`, `owner=<you>` (same convention as A1's CloudTrail resources).
- [ ] Create VPC.

---

## 2. Subnets — two public subnets, two AZs

- [ ] **VPC console → Subnets → Create subnet**.
- [ ] VPC: select `shvoy-dev-vpc`.
- [ ] Subnet 1:
  - Name: `shvoy-dev-public-eu-west-2a`
  - Availability Zone: `eu-west-2a`
  - IPv4 CIDR block: `10.0.1.0/24`
- [ ] **Add new subnet** for Subnet 2:
  - Name: `shvoy-dev-public-eu-west-2b`
  - Availability Zone: `eu-west-2b`
  - IPv4 CIDR block: `10.0.2.0/24`
- [ ] Tags on both: `env=dev`, `project=shvoy`, `owner=<you>`.
- [ ] Create subnet(s).
- [ ] **For each subnet**: select it → **Actions → Edit subnet settings** → check **"Enable auto-assign public IPv4 address"** → Save. This is the setting the no-NAT gotcha above depends on — easy to miss since it's off by default.

---

## 3. Internet connectivity

### 3a. Internet Gateway
- [ ] **VPC console → Internet Gateways → Create internet gateway**.
- [ ] Name: `shvoy-dev-igw`. Tags: same convention.
- [ ] Create, then **Actions → Attach to VPC** → select `shvoy-dev-vpc` → Attach.

### 3b. Route table
- [ ] **VPC console → Route Tables → Create route table**.
- [ ] Name: `shvoy-dev-public-rt`. VPC: `shvoy-dev-vpc`. Tags: same convention.
- [ ] Create, then select it → **Routes** tab → **Edit routes** → **Add route**: destination `0.0.0.0/0`, target → Internet Gateway → `shvoy-dev-igw`. Save.
- [ ] **Subnet associations** tab → **Edit subnet associations** → check both `shvoy-dev-public-eu-west-2a` and `shvoy-dev-public-eu-west-2b` → Save. (Deliberately a dedicated route table rather than relying on the VPC's implicit main route table — explicit is easier to audit later.)

---

## 4. Security groups — the chain that actually matters

Create in this order so each one can reference the previous by ID when you get to it (though the console will let you create all three empty first and add rules after, if you prefer — either order works, since editing rules later just needs the SGs to already exist).

All three: **EC2 console → Security Groups → Create security group**, VPC: `shvoy-dev-vpc`, tags: same convention throughout.

### 4a. ALB security group
- [ ] Name: `shvoy-dev-alb-sg`. Description: "Public-facing ALB — HTTPS only."
- [ ] Inbound rules: **HTTPS (443)**, source **Anywhere-IPv4** (`0.0.0.0/0`).
- [ ] Outbound: leave the default (**All traffic, 0.0.0.0/0**) — the ALB needs to reach the ECS tasks on their port, and restricting this further isn't asked for here.
- [ ] Create.

### 4b. ECS task security group
- [ ] Name: `shvoy-dev-ecs-sg`. Description: "ECS tasks — only reachable from the ALB."
- [ ] Inbound rule: **Custom TCP**, port **8080** (confirmed from the backend's `Dockerfile` — `EXPOSE 8080`, Spring Boot's default, no `server.port` override in the app config), **source: Custom → select the `shvoy-dev-alb-sg` security group** (type its name/ID into the source field so it resolves as a security-group reference, not a CIDR — this is the detail that makes the whole chain hold).
- [ ] Outbound: leave the default (**All traffic**) — tasks need HTTPS out to ECR, S3, Secrets Manager, and Cognito, all reachable via the IGW since there's no NAT. The default covers this without needing per-service rules.
- [ ] Create.

### 4c. RDS security group
- [ ] Name: `shvoy-dev-rds-sg`. Description: "RDS — only reachable from ECS tasks."
- [ ] Inbound rule: **PostgreSQL (5432)**, **source: Custom → select the `shvoy-dev-ecs-sg` security group** (again, a security-group reference, not a CIDR).
- [ ] Outbound: leave the default — doesn't meaningfully matter for RDS's usage pattern here, and nothing in the story asks for it to be restricted.
- [ ] Create.

### 4d. Verify the chain
- [ ] `shvoy-dev-alb-sg` inbound: 443 from `0.0.0.0/0` only.
- [ ] `shvoy-dev-ecs-sg` inbound: 8080 from `shvoy-dev-alb-sg` only (should show the SG name/ID as the source, not a CIDR).
- [ ] `shvoy-dev-rds-sg` inbound: 5432 from `shvoy-dev-ecs-sg` only (same — SG reference, not a CIDR).
- [ ] No security group has an inbound rule sourced from `0.0.0.0/0` except the ALB's 443 rule. This is the property the whole story exists to establish — worth a deliberate look before moving on.

---

## Decisions Log

| Decision | Value | Why |
|---|---|---|
| VPC CIDR | `10.0.0.0/16` | Plenty of headroom, standard/unambiguous range for a single dev VPC with no peering yet |
| Subnet layout | 2 public subnets, `eu-west-2a`/`eu-west-2b`, `/24` each | ALB requires 2 AZs minimum; no private tier since NAT is being skipped |
| NAT Gateway | **Skipped** — public subnets + strict SGs instead | ~$32/mo + data processing avoided; acceptable isolation trade-off for a pilot, revisit if compliance/traffic needs change |
| Public IP on subnets | Enabled (auto-assign) | Required for tasks/ALB to reach the internet at all without a NAT |
| ECS task port | `8080` | Confirmed from the backend `Dockerfile`'s `EXPOSE 8080`, not assumed |
| SG chain | ALB (443 from internet) → ECS (8080 from ALB SG) → RDS (5432 from ECS SG) | Core isolation property; each layer only trusts the layer in front of it, via SG references not CIDRs |

---

## Acceptance criteria checklist (from the story)

- [ ] A VPC exists in `eu-west-2` with the tagging convention applied
- [ ] Public subnets exist in two AZs (`eu-west-2a`, `eu-west-2b`)
- [ ] An Internet Gateway is attached and public subnets route to it correctly
- [ ] The NAT Gateway decision is made explicitly and documented (no NAT — see Decisions Log)
- [ ] Three security groups exist with the correct inbound chain (verified in Step 4d)
- [ ] Outbound rules allow tasks to reach the AWS services they depend on (default allow-all, sufficient for this story)
- [ ] No RDS instance, ECS service, or ALB was created in this story
