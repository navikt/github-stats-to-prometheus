package no.nav.github_stats

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.*

class RepoMetricsTest {
    private fun pr(
        title: String,
        login: String? = "octocat",
    ) = GraphQLPullRequest(title = title, authorLogin = login)

    private fun dependabotPr(title: String) = pr(title, login = "dependabot")

    private fun alert(severity: String) = GraphQLVulnerabilityAlert(severity = severity)

    private fun codeScanningAlert(severity: String?) = CodescanningAlert(CodescanningRule(severity))

    // --- openPRs ---

    @Test fun `openPRs counts all open PRs`() {
        val metrics = RepoMetrics(repository = "repo", pullRequests = listOf(pr("PR 1"), pr("PR 2"), dependabotPr("Bump dep")))
        assertEquals(3, metrics.openPRs)
    }

    @Test fun `openPRs is zero with no PRs`() {
        assertEquals(0, RepoMetrics(repository = "repo").openPRs)
    }

    // --- openDependenciesSum ---

    @Test fun `openDependenciesSum counts dependabot PRs`() {
        val metrics =
            RepoMetrics(
                repository = "repo",
                pullRequests =
                    listOf(
                        dependabotPr("Bump actions/checkout from 3 to 4"),
                        dependabotPr("Bump kotlin from 1.8 to 1.9"),
                        pr("Regular PR"),
                    ),
            )
        assertEquals(2, metrics.openDependenciesSum)
    }

    @Test fun `openDependenciesSum is zero with no dependabot PRs`() {
        val metrics = RepoMetrics(repository = "repo", pullRequests = listOf(pr("Regular PR")))
        assertEquals(0, metrics.openDependenciesSum)
    }

    @Test fun `openDependenciesSum excludes PRs with null author`() {
        val metrics = RepoMetrics(repository = "repo", pullRequests = listOf(pr("Some PR", login = null)))
        assertEquals(0, metrics.openDependenciesSum)
    }

    // --- dependabot alerts ---

    @Test fun `dependabotCritical counts only critical severity`() {
        val metrics =
            RepoMetrics(
                repository = "repo",
                vulnerabilityAlerts = listOf(alert("critical"), alert("critical"), alert("high"), alert("medium")),
            )
        assertEquals(2, metrics.dependabotCritical)
    }

    @Test fun `dependabotHigh counts only high severity`() {
        val metrics =
            RepoMetrics(
                repository = "repo",
                vulnerabilityAlerts = listOf(alert("critical"), alert("high"), alert("high"), alert("low")),
            )
        assertEquals(2, metrics.dependabotHigh)
    }

    @Test fun `dependabotTotal counts all alerts`() {
        val metrics =
            RepoMetrics(
                repository = "repo",
                vulnerabilityAlerts = listOf(alert("critical"), alert("high"), alert("medium"), alert("low")),
            )
        assertEquals(4, metrics.dependabotTotal)
    }

    @Test fun `dependabot metrics are zero with no alerts`() {
        val metrics = RepoMetrics(repository = "repo")
        assertEquals(0, metrics.dependabotCritical)
        assertEquals(0, metrics.dependabotHigh)
        assertEquals(0, metrics.dependabotTotal)
    }

    // --- code scanning alerts ---

    @Test fun `codeScanningCritical counts only critical severity`() {
        val metrics =
            RepoMetrics(
                repository = "repo",
                codeScanningAlerts = listOf(codeScanningAlert("critical"), codeScanningAlert("high"), codeScanningAlert(null)),
            )
        assertEquals(1, metrics.codeScanningCritical)
    }

    @Test fun `codeScanningTotal counts all alerts`() {
        val metrics =
            RepoMetrics(
                repository = "repo",
                codeScanningAlerts = listOf(codeScanningAlert("critical"), codeScanningAlert("high"), codeScanningAlert(null)),
            )
        assertEquals(3, metrics.codeScanningTotal)
    }

    @Test fun `code scanning metrics are zero with no alerts`() {
        val metrics = RepoMetrics(repository = "repo")
        assertEquals(0, metrics.codeScanningCritical)
        assertEquals(0, metrics.codeScanningTotal)
    }

    // --- daysSinceLatestCommit ---

    @Test fun `daysSinceLatestCommit is null when no commit date`() {
        assertNull(RepoMetrics(repository = "repo").daysSinceLatestCommit)
    }

    @Test fun `daysSinceLatestCommit computes correct days`() {
        val daysAgo = 10L
        val date =
            LocalDate
                .now()
                .minusDays(daysAgo)
                .atStartOfDay()
                .format(DateTimeFormatter.ISO_DATE_TIME)
        assertEquals(daysAgo, RepoMetrics(repository = "repo", latestCommitDate = date).daysSinceLatestCommit)
    }

    @Test fun `daysSinceLatestCommit is zero for today`() {
        val today = LocalDate.now().atStartOfDay().format(DateTimeFormatter.ISO_DATE_TIME)
        assertEquals(0L, RepoMetrics(repository = "repo", latestCommitDate = today).daysSinceLatestCommit)
    }

    // --- secretAlerts ---

    @Test fun `secretAlerts is reflected directly`() {
        assertEquals(5, RepoMetrics(repository = "repo", secretAlerts = 5).secretAlerts)
    }
}
