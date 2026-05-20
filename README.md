# github-stats-to-prometheus

A generic Docker image that queries the GitHub API for repository security and
activity metrics across one or more teams in a GitHub organisation, then pushes
the results to a Prometheus Pushgateway.

Runs as a one-shot batch job, no web server. Designed to be used as a
Kubernetes CronJob or NAIS Naisjob.

---

## Metrics collected

All repo-level metrics carry labels: `org`, `team`, `repository`.

| Metric | Description |
|---|---|
| `github_stats_open_prs` | Open pull requests |
| `github_stats_dependency_updates` | Open Dependabot PRs (group PRs expanded to individual dependency count) |
| `github_stats_dependabot_alerts_critical` | Open critical Dependabot CVE alerts |
| `github_stats_dependabot_alerts_high` | Open high Dependabot CVE alerts |
| `github_stats_secret_scanning_alerts` | Open secret scanning alerts |
| `github_stats_code_scanning_alerts_critical` | Open critical code scanning alerts |
| `github_stats_last_commit_age_days` | Days since the most recent commit |
| `github_stats_job_duration_seconds` | Wall-clock time for the full run |

---

## Configuration

| Env var | Required | Default | Description |
|---|---|---|---|
| `GITHUB_ORG` | Yes | — | GitHub organisation slug |
| `GITHUB_TEAMS` | Yes | — | Comma-separated team slugs |
| `GITHUB_API_URL` | No | `https://api.github.com/` | GitHub API base URL |
| `PUSH_GATEWAY_ADDRESS` | Yes | — | Prometheus Pushgateway URL. Set to `dummy` to skip push (local dev). |
| `MINIMUM_ROLE` | No | `push` | Minimum permission to include a repo: `pull`, `triage`, `push`, `maintain`, `admin`. |
| `EXCLUDED_REPOS` | No | — | Comma-separated repo names to exclude |
| `GITHUB_PAT` | Either/or | — | Fine-grained personal access token |
| `GITHUB_APP_ID` | Either/or | — | GitHub App numeric ID |
| `GITHUB_APP_PRIVATE_KEY` | Either/or | — | App private key, base64-encoded PEM |
| `GITHUB_APP_INSTALLATION_ID` | Either/or | — | GitHub App installation ID |

### Authentication

If `GITHUB_APP_ID` is present, GitHub App auth is used. Otherwise `GITHUB_PAT` is used.
The job fails immediately at startup if neither is configured.

#### Fine-grained PAT (simplest option)

Create a fine-grained PAT scoped to the target organisation with read-only permissions:

- **Repository permissions:** `Contents`, `Pull requests`, `Dependabot alerts`,
  `Secret scanning alerts`, `Code scanning alerts` — all set to **Read-only**
- **Organisation permissions:** `Members` — **Read-only**

Fine-grained PATs are preferred over classic PATs because permissions are minimal and
explicit. Classic PATs require the broad `repo` scope to access private/internal repos,
which grants write access.

#### GitHub App (recommended for production)

Set `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, and `GITHUB_APP_INSTALLATION_ID`.

The app fetches a short-lived installation token at startup (valid 1 hour, job runs in seconds).

Required GitHub App permissions:
- Repository: `Contents (read)`, `Pull requests (read)`, `Dependabot alerts (read)`,
  `Secret scanning alerts (read)`, `Code scanning alerts (read)`
- Organisation: `Members (read)`

To encode your private key for use as `GITHUB_APP_PRIVATE_KEY`:

```bash
base64 -i private-key.pem
```

### MINIMUM_ROLE

Repositories are included only when the team's permission meets or exceeds the minimum:

```
pull < triage < push < maintain < admin
```

---

## Running with Docker

```bash
docker run --rm \
  -e GITHUB_ORG=my-org \
  -e GITHUB_TEAMS=team-a,team-b \
  -e GITHUB_PAT=github_pat_... \
  -e PUSH_GATEWAY_ADDRESS=http://pushgateway:9091 \
  ghcr.io/your-org/github-stats-to-prometheus:latest
```

---

## Deploying as a NAIS Naisjob

Store all secrets in a single Kubernetes secret (or NAIS console secret). The job reads
every key from the secret as an environment variable via `envFrom`.

**Required secret keys:**

| Key | Description |
|---|---|
| `GITHUB_ORG` | GitHub organisation slug |
| `GITHUB_TEAMS` | Comma-separated team slugs |
| `GITHUB_PAT` | Fine-grained PAT — **or** use the three App keys below |
| `GITHUB_APP_ID` | GitHub App ID |
| `GITHUB_APP_PRIVATE_KEY` | Base64-encoded PEM private key |
| `GITHUB_APP_INSTALLATION_ID` | Installation ID |

Use the following as a starting point for `.nais/nais.yaml`. Adjust `team`, `namespace`,
`name`, and the secret name to match your setup:

```yaml
apiVersion: nais.io/v1
kind: Naisjob
metadata:
  labels:
    team: my-team
  name: my-team-github-stats
  namespace: my-team
spec:
  image: "{{ image }}"
  schedule: "0 * * * *"
  timeZone: Europe/Oslo
  failedJobsHistoryLimit: 1
  successfulJobsHistoryLimit: 1
  backoffLimit: 1
  envFrom:
    - secret: my-team-github-stats   # Kubernetes secret containing the keys listed above
  env:
    - name: PUSH_GATEWAY_ADDRESS
      value: "prometheus-pushgateway.nais-system:9091"
    - name: GITHUB_API_URL
      value: "https://api.github.com/"
  accessPolicy:
    outbound:
      rules:
        - application: prometheus-pushgateway
          namespace: nais-system
      external:
        - host: "api.github.com"
```

---

## Running as a Kubernetes CronJob

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: github-stats
spec:
  schedule: "0 * * * *"
  jobTemplate:
    spec:
      template:
        spec:
          restartPolicy: Never
          containers:
          - name: github-stats
            image: ghcr.io/your-org/github-stats-to-prometheus:latest
            envFrom:
              - secretRef:
                  name: github-stats-secrets   # contains GITHUB_ORG, GITHUB_TEAMS, GITHUB_PAT etc.
            env:
            - name: PUSH_GATEWAY_ADDRESS
              value: http://pushgateway.monitoring:9091
```

---

## Grafana examples

All examples assume the default label set: `org`, `team`, `repository`.

### Open PRs per team

```promql
sum by (team) (github_stats_open_prs)
```

### Critical security alerts — top repositories

```promql
topk(10,
  github_stats_dependabot_alerts_critical
  + github_stats_code_scanning_alerts_critical
  + github_stats_secret_scanning_alerts
)
```

### Repositories with unmerged Dependabot PRs > 10

```promql
github_stats_dependency_updates > 10
```

### Stale repositories (no commit in 90 days)

```promql
github_stats_last_commit_age_days > 90
```

### Security alert heatmap — all repos in an org

Use a **Table** panel with the following columns:

```promql
# Critical CVEs
github_stats_dependabot_alerts_critical{org="my-org"}

# High CVEs
github_stats_dependabot_alerts_high{org="my-org"}

# Secret leaks
github_stats_secret_scanning_alerts{org="my-org"}
```

Transform with **Merge** and sort by `Value #A` descending to surface the worst repos first.

---

## Building locally

```bash
./gradlew installDist
docker build -t github-stats-to-prometheus .
```

To run tests:

```bash
./gradlew test
```

---

## Adding new metrics

1. Add a `suspend fun` to `GitHubClient.kt` for the new API call.
2. Add a field to `RepoMetrics` in `Main.kt`.
3. Add a `Gauge` to `MetricsRegistry` in `Metrics.kt` and set the value in `record()`.
4. Call the new client method in `fetchRepoMetrics()` in `Main.kt`.

---

## Contact

For any questions, issues, or feature requests, please reach out to the AppSec team:
- Internal: Either our slack channel [#appsec](https://nav-it.slack.com/archives/C06P91VN27M) or contact a [team member](https://teamkatalogen.nav.no/team/02ed767d-ce01-49b5-9350-ee4c984fd78f) directly via slack/teams/mail.
- External: [Open GitHub Issue](https://github.com/navikt/appsec-guide/issues/new/choose)

## Code generated by GitHub Copilot

This project was developed with the assistance of GitHub Copilot, an AI-powered code completion tool.
