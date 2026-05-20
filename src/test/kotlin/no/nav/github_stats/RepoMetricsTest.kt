package no.nav.github_stats

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.*

class RepoMetricsTest {

    private fun pr(title: String, login: String = "octocat") =
        PullRequest(updated_at = "2024-01-01T00:00:00Z", title = title, user = User(login = login, type = "User"))

    private fun dependabotPr(title: String) = pr(title, login = "dependabot[bot]")

    private fun alert(severity: String) = DependabotAlert(SecurityVulnerability(severity))

    private fun commitOn(date: String) = Commit(CommitDetails(CommitAuthor(date)))

    @Test fun `openDependenciesSum counts individual dependabot PRs`() {
        val metrics = RepoMetrics(
            repository = "repo",
            openPRs = 3,
            dependabotPrs = listOf(
                dependabotPr("Bump actions/checkout from 3 to 4"),
                dependabotPr("Bump kotlin from 1.8 to 1.9"),
                dependabotPr("Bump logback from 1.4 to 1.5")
            )
        )
        assertEquals(3, metrics.openDependenciesSum)
    }

    @Test fun `openDependenciesSum expands grouped dependabot PR`() {
        val metrics = RepoMetrics(
            repository = "repo",
            openPRs = 1,
            dependabotPrs = listOf(dependabotPr("Bump the logging group across 1 directory with 3 updates"))
        )
        assertEquals(3, metrics.openDependenciesSum)
    }

    @Test fun `openDependenciesSum handles mix of grouped and individual PRs`() {
        val metrics = RepoMetrics(
            repository = "repo",
            openPRs = 3,
            dependabotPrs = listOf(
                dependabotPr("Bump the logging group across 1 directory with 4 updates"),
                dependabotPr("Bump actions/checkout from 3 to 4"),
                dependabotPr("Bump kotlin from 1.8 to 1.9")
            )
        )
        assertEquals(6, metrics.openDependenciesSum)
    }

    @Test fun `openDependenciesSum is zero with no dependabot PRs`() {
        val metrics = RepoMetrics(repository = "repo", openPRs = 2, dependabotPrs = emptyList())
        assertEquals(0, metrics.openDependenciesSum)
    }

    @Test fun `openDependenciesSum ignores non-dependabot PRs`() {
        val metrics = RepoMetrics(
            repository = "repo",
            openPRs = 2,
            dependabotPrs = listOf(dependabotPr("Bump dep from 1 to 2"))
        )
        assertEquals(1, metrics.openDependenciesSum)
    }

    @Test fun `criticalAlertsSum counts only critical severity`() {
        val metrics = RepoMetrics(
            repository = "repo", openPRs = 0, dependabotPrs = emptyList(),
            dependabotAlerts = listOf(alert("critical"), alert("critical"), alert("high"), alert("medium"))
        )
        assertEquals(2, metrics.criticalAlertsSum)
    }

    @Test fun `highAlertsSum counts only high severity`() {
        val metrics = RepoMetrics(
            repository = "repo", openPRs = 0, dependabotPrs = emptyList(),
            dependabotAlerts = listOf(alert("critical"), alert("high"), alert("high"), alert("low"))
        )
        assertEquals(2, metrics.highAlertsSum)
    }

    @Test fun `criticalAlertsSum and highAlertsSum are zero with no alerts`() {
        val metrics = RepoMetrics(repository = "repo", openPRs = 0, dependabotPrs = emptyList())
        assertEquals(0, metrics.criticalAlertsSum)
        assertEquals(0, metrics.highAlertsSum)
    }

    @Test fun `daysSinceLatestCommit is null when no commit`() {
        val metrics = RepoMetrics(repository = "repo", openPRs = 0, dependabotPrs = emptyList(), latestCommit = null)
        assertNull(metrics.daysSinceLatestCommit)
    }

    @Test fun `daysSinceLatestCommit computes correct days`() {
        val daysAgo = 10L
        val date = LocalDate.now().minusDays(daysAgo).atStartOfDay().format(DateTimeFormatter.ISO_DATE_TIME)
        val metrics = RepoMetrics(
            repository = "repo", openPRs = 0, dependabotPrs = emptyList(),
            latestCommit = commitOn(date)
        )
        assertEquals(daysAgo, metrics.daysSinceLatestCommit)
    }

    @Test fun `daysSinceLatestCommit is zero for today`() {
        val today = LocalDate.now().atStartOfDay().format(DateTimeFormatter.ISO_DATE_TIME)
        val metrics = RepoMetrics(
            repository = "repo", openPRs = 0, dependabotPrs = emptyList(),
            latestCommit = commitOn(today)
        )
        assertEquals(0L, metrics.daysSinceLatestCommit)
    }
}
