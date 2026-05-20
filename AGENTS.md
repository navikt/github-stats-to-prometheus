# AGENTS.md

## What this repo is

A generic Kotlin batch job (Docker image) that queries the GitHub API for security
and activity metrics across repositories owned by one or more teams in a GitHub
organisation, then pushes results to a Prometheus Pushgateway.

Not a web service. Designed to run as a cron job (e.g. Kubernetes CronJob / NAIS Naisjob).

## Build & run

```bash
./gradlew installDist      # compiles + stages to build/install/github-stats/{bin,lib}
./gradlew test             # no tests exist yet — succeeds vacuously
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
GITHUB_TEAMS=team-a,team-b
PUSH_GATEWAY_ADDRESS=dummy
GITHUB_API_URL=https://api.github.com/
```

Auth — choose one:

```
# PAT
GITHUB_PAT=ghp_...

# GitHub App
GITHUB_APP_ID=123456
GITHUB_APP_INSTALLATION_ID=78901234
GITHUB_APP_PRIVATE_KEY=<base64-encoded PEM>
```

`PUSH_GATEWAY_ADDRESS=dummy` skips the push step (magic sentinel checked in `Metrics.kt`).

## Auth detection

If `GITHUB_APP_ID` is present → GitHub App mode (JWT → installation token via `GitHubAuth.kt`).
Otherwise → PAT mode (`GITHUB_PAT`).
Fails fast at startup if neither is set. Key material is never logged.

## MINIMUM_ROLE

Controls which repos are included. Hierarchy (low → high):

```
pull < triage < push < maintain < admin
```

Default: `push`. Setting `maintain` includes repos where the team has maintain or admin.
The `permissions` object from the GitHub API is used directly — no extra API calls.
Implemented in `Config.kt` as the `Role` enum with `meetsMinimum()`.

## Architecture

Five source files, all in `src/main/kotlin/no/nav/github_stats/`:

| File | Responsibility |
|---|---|
| `Config.kt` | Env var parsing, fail-fast validation, `Role` enum, `AuthMode` sealed class |
| `GitHubAuth.kt` | Resolves Bearer token: PAT passthrough or App JWT → installation token |
| `GitHubClient.kt` | All GitHub REST calls, automatic pagination (follows `Link: rel="next"`), per-call error isolation |
| `Metrics.kt` | Prometheus gauge registration, label helpers, push logic |
| `Main.kt` | Thin orchestrator only — wires everything together |

All GitHub API calls are sequential (`runBlocking`). Retries are handled by the Ktor
`HttpRequestRetry` plugin at the HTTP client level: 3 attempts, exponential backoff
(1s → 2s → 4s), on IOException and 5xx only. Per-repo fetch errors are caught, logged
at WARN, and return empty/zero so a single bad repo never stops the job.

## Prometheus metrics

Namespace: `github_stats`. Labels on all repo-level metrics: `org`, `team`, `repository`.

Single push per run: `job=github-stats`, `instance={org}`. Atomically replaces the entire
metric group for the org on each run (stale series from removed repos are cleaned up).

## Adding new data points

1. Add a `suspend fun` to `GitHubClient.kt` for the new API call.
2. Add a field to `RepoMetrics` in `Main.kt`.
3. Add a `Gauge` to `MetricsRegistry` in `Metrics.kt` and call `record()` with the new value.
4. Call the new client method in `fetchRepoMetrics()` in `Main.kt`.

No config or auth changes needed unless the new endpoint requires additional GitHub permissions.

## CI/CD

| Trigger | Workflow | Steps |
|---|---|---|
| PR | `pr.yml` | `dependency-review-action` + `./gradlew test build` (Java 25) |
| Push to `main` | `deploy-job.yml` | test → NAIS docker build/push + dependency graph → NAIS deploy (`prod-gcp`) → Trivy secret scan |

- Pinned commit SHAs in all workflow steps — keep them pinned.
- CI uses Java 25 (`actions/setup-java` with `temurin` distribution).
- The `build` job in `deploy-job.yml` uses `nais/docker-build-push` (team: `appsec`), which runs `installDist` via the Dockerfile internally.
