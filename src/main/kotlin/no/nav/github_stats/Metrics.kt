package no.nav.github_stats

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.PushGateway
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Metrics")
private val LABELS = arrayOf("org", "team", "repository")

class MetricsRegistry {
    val registry = CollectorRegistry()

    val jobDuration = gauge("github_stats_job_duration_seconds", "Wall-clock seconds for the full run", *arrayOf())
    val openPrs               = gauge("github_stats_open_prs",                      "Open pull requests")
    val dependencyUpdates     = gauge("github_stats_dependency_updates",             "Open Dependabot PRs (group PRs expanded to individual count)")
    val dependabotCritical    = gauge("github_stats_dependabot_alerts_critical",     "Open critical Dependabot CVE alerts")
    val dependabotHigh        = gauge("github_stats_dependabot_alerts_high",         "Open high Dependabot CVE alerts")
    val secretAlerts          = gauge("github_stats_secret_scanning_alerts",         "Open secret scanning alerts")
    val codeScanningCritical  = gauge("github_stats_code_scanning_alerts_critical",  "Open critical code scanning alerts")
    val lastCommitAgeDays     = gauge("github_stats_last_commit_age_days",           "Days since most recent commit")

    private fun gauge(name: String, help: String, vararg labelNames: String = LABELS): Gauge =
        Gauge.build().name(name).help(help).labelNames(*labelNames).register(registry)

    fun record(org: String, team: String, repo: RepoMetrics) {
        val l = arrayOf(org, team, repo.repository)
        openPrs.labels(*l).set(repo.openPRs.toDouble())
        dependencyUpdates.labels(*l).set(repo.openDependenciesSum.toDouble())
        dependabotCritical.labels(*l).set(repo.criticalAlertsSum.toDouble())
        dependabotHigh.labels(*l).set(repo.highAlertsSum.toDouble())
        secretAlerts.labels(*l).set(repo.secretAlerts.toDouble())
        codeScanningCritical.labels(*l).set(repo.codeScanningCriticalAlerts.toDouble())
        repo.daysSinceLatestCommit?.let { lastCommitAgeDays.labels(*l).set(it.toDouble()) }
    }

    fun push(address: String, org: String) {
        if (address == "dummy") return log.info("Skipping push (dummy)")
        log.info("Pushing metrics to $address")
        PushGateway(address).push(registry, "github-stats", mapOf("instance" to org))
        log.info("Metrics pushed successfully")
    }

    fun startTimer(): () -> Unit = jobDuration.startTimer()::setDuration
}
