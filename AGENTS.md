# AGENTS.md

## What this repo is

A Kotlin batch job (Docker image) that queries the GitHub API for security and activity
metrics across repositories owned by one or more teams in a GitHub organisation, then
pushes results to a Prometheus Pushgateway.

Not a web service. Designed to run as a cron job (e.g. Kubernetes CronJob / NAIS Naisjob).

## Build & run

```bash
./gradlew installDist      # compiles + stages to build/install/github-stats/{bin,lib}
./gradlew test
./gradlew clean installDist
```

No fat JAR. Uses the built-in Gradle `application` plugin (`installDist` task).
No linter or formatter configured (no ktlint, no detekt). `kotlin.code.style=official` only.

## Dependency management

All dependency versions live in `gradle/libs.versions.toml` (version catalog).
Reference them in `build.gradle.kts` as `libs.<alias>` — never add raw version strings
to the build script. To add a new dependency:
1. Add the version to `[versions]` and the module to `[libraries]` in `libs.versions.toml`.
2. Reference it with `implementation(libs.<alias>)` in `build.gradle.kts`.

## Local run

Required env vars:

```
GITHUB_ORG=navikt
PUSH_GATEWAY_ADDRESS=dummy
GITHUB_APP_ID=123456
GITHUB_APP_INSTALLATION_ID=78901234
GITHUB_APP_PRIVATE_KEY=<raw PEM>
```

`PUSH_GATEWAY_ADDRESS=dummy` skips the push step (magic sentinel checked in `Metrics.kt`).

## Auth

GitHub App only. Requires `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY` (raw PEM), and
`GITHUB_APP_INSTALLATION_ID`. Fails fast at startup if any are missing or non-numeric.
A JWT is signed with BouncyCastle + auth0-java-jwt, exchanged for an installation token
via the GitHub REST API. Key material is never logged.

## Architecture

Six source files, all in `src/main/kotlin/no/nav/github_stats/`:

| File | Responsibility |
|---|---|
| `Config.kt` | Env var parsing, fail-fast validation |
| `GitHubAuth.kt` | GitHub App JWT → installation token |
| `GitHubClient.kt` | REST calls: org teams, team repos, secret/code scanning alerts; pagination via `Link: rel="next"`; semaphore(20) |
| `GitHubGraphQLClient.kt` | Batched GraphQL: PRs, vulnerability alerts, latest commit date; 20 repos/query; pagination |
| `Metrics.kt` | Prometheus gauge registration, label helpers, push logic |
| `Main.kt` | Thin orchestrator — wires everything together |

All GitHub API calls run inside `runBlocking`. Retries are handled by the Ktor
`HttpRequestRetry` plugin: 3 attempts, exponential backoff, on IOException and 5xx only.
Per-repo fetch errors are caught, logged at WARN, and return empty/zero so a single bad
repo never stops the job.

## Data sources

Hybrid approach: GraphQL for bulk data, REST for endpoints with no GraphQL equivalent.

| Data | Source |
|---|---|
| Org team list | REST (`/orgs/{org}/teams`) |
| Team repo list | REST (`/orgs/{org}/teams/{team}/repos`) |
| Open PRs | GraphQL |
| Dependabot vulnerability alerts | GraphQL |
| Latest commit date | GraphQL |
| Secret scanning alerts | REST (`/repos/{org}/{repo}/secret-scanning/alerts`) |
| Code scanning alerts | REST (`/repos/{org}/{repo}/code-scanning/alerts`) |

GraphQL batches 20 repos per query using field aliases. REST calls for secret/code scanning
run in parallel with `semaphore(20)`. navikt is GitHub Enterprise Cloud: REST limit
15,000 req/hour, GraphQL 10,000 points/hour.

## Prometheus metrics

Namespace: `github_stats`. Labels on all metrics: `org`, `team`, `repository`.

| Metric | Description |
|---|---|
| `github_stats_open_prs` | Open pull requests |
| `github_stats_dependency_updates` | Open Dependabot PRs (grouped PRs expanded to individual count) |
| `github_stats_dependabot_alerts_critical` | Critical Dependabot CVE alerts |
| `github_stats_dependabot_alerts_high` | High Dependabot CVE alerts |
| `github_stats_dependabot_alerts_total` | All open Dependabot CVE alerts |
| `github_stats_secret_scanning_alerts` | Open secret scanning alerts |
| `github_stats_code_scanning_alerts_critical` | Critical code scanning alerts |
| `github_stats_code_scanning_alerts_total` | All open code scanning alerts |
| `github_stats_last_commit_age_days` | Days since most recent commit on default branch |

Single push per run: `job=github-stats`, `instance={org}`. Atomically replaces the entire
metric group for the org on each run (stale series from removed repos are cleaned up).

## Adding new data points

### New REST endpoint
1. Add a `suspend fun` to `GitHubClient.kt`.
2. Add a field to `RepoMetrics` in `Main.kt`.
3. Add a `Gauge` to `MetricsRegistry` in `Metrics.kt` and call `record()` with the new value.
4. Call the new client method in `Main.kt`.

### New GraphQL field
1. Extend the query fragment in `GitHubGraphQLClient.kt` and update `GraphQLRepoData`.
2. Follow steps 2–4 above.

## CI/CD

| Trigger | Workflow | Steps |
|---|---|---|
| PR | `pr.yml` | `dependency-review-action` + `./gradlew test build` (Java 25) |
| Push to `main` | `deploy-job.yml` | test → NAIS docker build/push + dependency graph → NAIS deploy (`prod-gcp`) → Trivy secret scan |

- Pinned commit SHAs in all workflow steps — keep them pinned.
- CI uses Java 25 (`actions/setup-java` with `temurin` distribution).
- The `build` job in `deploy-job.yml` uses `nais/docker-build-push` (team: `appsec`), which runs `installDist` via the Dockerfile internally.
