# github-stats-to-prometheus

A Kotlin batch job that queries the GitHub API for security and activity metrics across
repositories owned by one or more teams in a GitHub organisation, then pushes the results
to a Prometheus Pushgateway.

Runs as a one-shot batch job — no web server. Designed for use as a Kubernetes CronJob
or NAIS Naisjob.

---

## Metrics collected

All metrics carry labels: `org`, `team`, `repository`.

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
| `github_stats_last_commit_age_days` | Days since the most recent commit on the default branch |

---

## Configuration

| Env var | Required | Default | Description |
|---|---|---|---|
| `GITHUB_ORG` | Yes | — | GitHub organisation slug |
| `GITHUB_APP_ID` | Yes | — | GitHub App numeric ID |
| `GITHUB_APP_PRIVATE_KEY` | Yes | — | App private key, raw PEM |
| `GITHUB_APP_INSTALLATION_ID` | Yes | — | GitHub App installation ID |
| `PUSH_GATEWAY_ADDRESS` | Yes | — | Prometheus Pushgateway address. Set to `dummy` to skip push (local dev). |
| `GITHUB_API_URL` | No | `https://api.github.com/` | GitHub API base URL (override for GHES) |
| `GITHUB_API_VERSION` | No | `2026-03-10` | `X-GitHub-Api-Version` header value |
| `GITHUB_GRAPHQL_URL` | No | `https://api.github.com/graphql` | GraphQL endpoint (override for GHES) |

### Authentication

GitHub App only. A short-lived installation token is fetched at startup using a JWT signed
with the app's private key. The token is valid for one hour; the job completes in seconds.
Key material is never logged.

Required GitHub App permissions:
- Repository: `Contents (read)`, `Pull requests (read)`, `Dependabot alerts (read)`,
  `Secret scanning alerts (read)`, `Code scanning alerts (read)`
- Organisation: `Members (read)`

---

## Contact

- Internal: [#appsec](https://nav-it.slack.com/archives/C06P91VN27M) or a [team member](https://teamkatalogen.nav.no/team/02ed767d-ce01-49b5-9350-ee4c984fd78f)
- External: [Open a GitHub issue](https://github.com/navikt/appsec-guide/issues/new/choose)
