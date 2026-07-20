# shvoy

SHVOY is a B2B procurement platform. This repo is a Java/Spring Boot modular
monolith deployed on AWS.

## Prerequisites

- **Java 25** (JDK). Check with `java -version`.
- **Docker** (for Postgres and LocalStack via docker-compose).
- No local Maven install needed — this repo includes the Maven Wrapper
  (`./mvnw`), which downloads the correct Maven version on first use.

## Running locally

1. Start the local dependencies (Postgres + LocalStack for S3):

   ```
   docker compose up -d
   ```

2. Run the app against the `local` profile:

   ```
   ./mvnw spring-boot:run
   ```

   (`local` is the default active profile, so no `-Dspring-boot.run.profiles`
   flag is required. The app also auto-starts/stops `docker-compose.yml` for
   you via `spring-boot-docker-compose` if you skip step 1 and Docker is
   already running.)

3. Confirm it's up:

   ```
   curl http://localhost:8080/actuator/health
   curl http://localhost:8080/api/suppliers/ping
   ```

## Running tests

- **Unit / fast tests** (no Docker required):

  ```
  ./mvnw test
  ```

  This includes the Spring Modulith module-boundary check
  (`ModularityTests`) and a context-load smoke test run against an
  in-memory H2 database.

- **Integration tests** (require Docker; spin up their own containers via
  Testcontainers, independent of `docker compose up`):

  ```
  ./mvnw verify
  ```

  This runs `*IT.java` tests, e.g. `S3ConnectivityIT`, which verifies S3
  upload/download against a throwaway LocalStack container.

## Building the container image

```
docker build -t shvoy .
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/shvoy \
  shvoy
```

(`host.docker.internal` reaches the `docker compose up` Postgres from inside
the container on macOS/Windows Docker Desktop. On Linux, add
`--add-host=host.docker.internal:host-gateway` to the `docker run` command.)

Multi-stage build: Maven + JDK 25 to compile, then a slim JRE-only Alpine
runtime image.

## Module structure

The app is a single deployable (one JAR) structured as a modular monolith
using [Spring Modulith](https://spring.io/projects/spring-modulith). Each
top-level package under `com.shvoy` is a verified module boundary — cross-module
calls should go through explicit APIs or application events, not direct
internal-class access. `ModularityTests` fails the build if a boundary is
violated.

Modules, one per feature area:

- `onboarding`
- `suppliers`
- `purchaseorders`
- `reconciliation`
- `shipments`
- `payments`
- `containerfill`
- `dashboard`

Each module follows the same internal layout:

```
com.shvoy.<module>/
  controller/   REST controllers
  service/      application services
  repository/   Spring Data repositories
  domain/       entity/domain model
  dto/          request/response DTOs
```

Code shared across modules (security config, S3 client, etc.) lives directly
in the `com.shvoy` root package, not inside a module package.

Database migrations live in `src/main/resources/db/migration`, applied by
Flyway on startup. Every module adds its own versioned migrations there as
it builds out its schema.

## What's not here yet

AWS account setup, CI/CD pipelines, and infrastructure-as-code are covered
by a separate infrastructure feature, not this one. This repo currently
targets local development only.
