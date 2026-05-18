package no.nav.github_stats

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Gauge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

val logger = LoggerFactory.getLogger("Main")

suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
    if (!status.isSuccess()) {
        throw RuntimeException("HTTP $status: ${bodyAsText()}")
    }
    return body<T>()
}


fun main() {
    val applicationContext: ApplicationContext = ApplicationContext.Builder().build()
    val githubTeams = listOf("k9saksbehandling", "omsorgspenger", "dusseldorf")
    val githubApiUrl = applicationContext.githubApiUrl

    val collectorRegistry = CollectorRegistry()

    val httpClient = applicationContext.httpClient

    val teamRepositories = findTeamRepositories(githubTeams, httpClient, githubApiUrl)

    logger.info("Alle repositores (${teamRepositories.size}): ${teamRepositories.map { it }}")

    val repositoryInfos: List<RepositoryInfo> = findRepositoryInfo(httpClient, githubApiUrl, teamRepositories)



    val jobTimer = Gauge.build()
        .name("sif_github_stats_last_job_timer")
        .help("Time it took to run last job")
        .register(collectorRegistry)
        .startTimer()
    val totalPrGauge = Gauge.build()
        .name("sif_github_stats_open_prs")
        .labelNames("repository")
        .help("Open Github PRs")
        .register(collectorRegistry)

    val totalDependabotGauge = Gauge.build()
        .name("sif_github_stats_dependency_updates")
        .labelNames("repository")
        .help("Dependencies awaiting merge")
        .register(collectorRegistry)

    val totalCriticalGauge = Gauge.build()
        .name("sif_github_stats_dependabot_critical")
        .labelNames("repository")
        .help("Dependabot Critical Alerts")
        .register(collectorRegistry)

    val totalHighGauge = Gauge.build()
        .name("sif_github_stats_dependabot_high")
        .labelNames("repository")
        .help("Dependabot High Alerts")
        .register(collectorRegistry)

    val totalSecretAlertGauge = Gauge.build()
        .name("sif_github_stats_secret_alerts")
        .labelNames("repository")
        .help("Exposed secrets alerts")
        .register(collectorRegistry)

    val totalCodeScanningGauge = Gauge.build()
        .name("sif_github_stats__code_scanning_criticals")
        .labelNames("repository")
        .help("Code scanning critical alerts")
        .register(collectorRegistry)

    val totalLastCommitDaysGauge = Gauge.build()
        .name("sif_github_stats_last_commit_days")
        .labelNames("repository")
        .help("Days since last commit")
        .register(collectorRegistry)




    try {
        repositoryInfos.forEach {
            logger.info("Setting metrics for Repository: $it")
            totalPrGauge
                .labels(it.repository)
                .set(it.openPRs.toDouble())

            totalDependabotGauge
                .labels(it.repository)
                .set(it.openDependenciesSum.toDouble())

            totalCriticalGauge
                .labels(it.repository)
                .set(it.criticalAlertsSum.toDouble())

            totalHighGauge
                .labels(it.repository)
                .set(it.highAlertsSum.toDouble())

            totalSecretAlertGauge
                .labels(it.repository)
                .set(it.secretAlerts.toDouble())

            totalCodeScanningGauge
                .labels(it.repository)
                .set(it.codeScanningCriticalAlerts.toDouble())

            totalLastCommitDaysGauge
                .labels(it.repository)
                .set(it.daysSinceLatestCommit.toDouble())


        }
    } finally {
        jobTimer.setDuration()
        logger.info("Pushing metrics")
        if (System.getenv()["PUSH_GATEWAY_ADDRESS"] != "dummy") {
            applicationContext.pushGateway
                .push(collectorRegistry, "sif-github-stats")
        }

    }

    generateLogReport(repositoryInfos)

    logger.info("Finished job successfully, exiting")
}

private fun findTeamRepositories(
    githubTeams: List<String>,
    httpClient: HttpClient,
    githubApiUrl: String
): Set<String> {
    val teamAndRepositories = runBlocking {
        githubTeams.mapNotNull {
            val urlString = githubApiUrl + "orgs/navikt/teams/$it/repos"
            try {
                val response = httpClient.get(urlString) {
                    parameter("per_page", "100")
                }
                val body = response.bodyOrThrow<List<OrgRepository>>()
                it to body
            } catch (e: Exception) {
                logger.error("Error fetching repositories for team: $it: ${e.message}", e)
                null
            }
        }
    }

    val repositories = teamAndRepositories.flatMap { it.second }
    logger.info("Found ${repositories.size} repositories for team(s) ${githubTeams.joinToString { it }} }}")

    teamAndRepositories.forEach { (team, repositories) ->
        val filtered = repositories.filter {
            (it.permissions.push || it.permissions.admin || it.permissions.maintain) && !it.archived
        }.map { it.name }.toSet()
        logger.info("Found ${filtered.size} repositories for team $team: $filtered")
    }

    // Filter out archived repos & repos that doesn't belong to the team and duplicates
    val teamRepositories = repositories.filter {
        (it.permissions.push || it.permissions.admin || it.permissions.maintain) && !it.archived
                && it.name != "arbeidsgiver-notifikasjon-produsenter" // Repo som innholder bare config.
    }
        .map { it.name }.toSet()
    logger.info("Filtered out ${repositories.size - teamRepositories.size} repositories")

    return teamRepositories
}

private fun findRepositoryInfo(
    httpClient: HttpClient,
    githubApiUrl: String,
    teamRepositories: Set<String>
): List<RepositoryInfo> {
    val repositoryInfo: List<RepositoryInfo> = runBlocking {
        teamRepositories.mapNotNull { repository ->
            // Fetch all open pull requests from repository and find total size and dependabots PRs
            try {
                val response = httpClient.get(githubApiUrl + "repos/navikt/$repository/pulls") {
                    parameter("per_page", "100")
                    parameter("state", "open")
                }
                val pullRequests = response.bodyOrThrow<List<PullRequest>>()
                RepositoryInfo(
                    repository,
                    pullRequests.size,
                    pullRequests.filter { it.user.login == "dependabot[bot]" })
            } catch (e: Exception) {
                logger.error("Error fetching open pull requests for repository: $repository: ${e.message}", e)
                null
            }
        }
    }

    logger.info("Received ${repositoryInfo.size} repositories with open pull requests")

    runBlocking {
        repositoryInfo.forEach {
            try {
                val response = httpClient.get(githubApiUrl + "repos/navikt/${it.repository}/dependabot/alerts") {
                    parameter("per_page", "100")
                    parameter("state", "open")
                }
                it.dependabotAlerts = response.bodyOrThrow<List<DependabotAlert>>()
            } catch (e: Exception) {
                logger.error("Error fetching dependabot alerts for repository: ${it.repository}: ${e.message}", e)
            }
        }
    }

    logger.info("Done getting dependabot alerts for ${repositoryInfo.size} repositories")


    runBlocking {
        repositoryInfo.forEach {
            try {
                val response = httpClient.get(githubApiUrl + "repos/navikt/${it.repository}/commits") {
                    parameter("per_page", "1")
                }
                val commits = response.bodyOrThrow<List<Commit>>()
                it.latestCommit = commits.firstOrNull() ?: throw IllegalStateException("No commits found")
            } catch (e: Exception) {
                logger.error("Error fetching latest commit for repository: ${it.repository}: ${e.message}", e)
            }
        }
    }

    logger.info("Done getting latest commit for ${repositoryInfo.size} repositories")


    runBlocking {
        repositoryInfo.forEach {
            try {
                val response = httpClient.get(githubApiUrl + "repos/navikt/${it.repository}/secret-scanning/alerts") {
                    parameter("per_page", "100")
                    parameter("state", "open")
                }
                it.secretAlerts = response.bodyOrThrow<List<SecretAlert>>().size
            } catch (e: Exception) {
                logger.error("Error fetching secret alerts for repository: ${it.repository}: ${e.message}", e)
            }
        }
    }

    logger.info("Done getting secret alerts for ${repositoryInfo.size} repositories")


    runBlocking {
        repositoryInfo.forEach {
            try {
                val response = httpClient.get(githubApiUrl + "repos/navikt/${it.repository}/code-scanning/alerts") {
                    parameter("per_page", "100")
                    parameter("state", "open")
                    parameter("severity", "critical")
                }
                it.codeScanningCriticalAlerts = response.bodyOrThrow<List<CodescanningAlert>>().size
            } catch (e: Exception) {
                logger.error("Error fetching code scanning alerts for repository: ${it.repository}: ${e.message}", e)
            }
        }
    }

    logger.info("Done getting code scanning alerts for ${repositoryInfo.size} repositories")

    return repositoryInfo
}

data class RepositoryInfo(
    val repository: String,
    val openPRs: Int,
    val dependabotPrs: List<PullRequest>,
    var dependabotAlerts: List<DependabotAlert> = emptyList(),
    var secretAlerts: Int = 0,
    var codeScanningCriticalAlerts: Int = 0,
    var latestCommit: Commit? = null
) {
    companion object {
        private val dependabotGroupUpdatesRegEx = "(\\d+)\\s+updates?$".toRegex()
        private val dependabotGroupRegEx = ".+group.+directory.+updates?$".toRegex()
    }

    val openDependenciesSum by lazy {
        val prTitles = dependabotPrs.map { it.title }.toSet()
        val groupTitles = prTitles.filter { dependabotGroupRegEx.matches(it) }.toSet()
        val groupDependencySums =
            groupTitles.sumOf { dependabotGroupUpdatesRegEx.find(it)?.groups?.lastOrNull()?.value?.toInt() ?: 0 }
        prTitles.minus(groupTitles).size + groupDependencySums
    }
    val criticalAlertsSum by lazy { dependabotAlerts.filter { it.security_vulnerability.severity == "critical" }.size }
    val highAlertsSum by lazy { dependabotAlerts.filter { it.security_vulnerability.severity == "high" }.size }
    override fun toString(): String {
        return "RepositoryInfo(repository='$repository', openPRs=$openPRs, secretAlerts=$secretAlerts, codeScanningCriticalAlerts=$codeScanningCriticalAlerts, openDependenciesSum=$openDependenciesSum, criticalAlertsSum=$criticalAlertsSum, highAlertsSum=$highAlertsSum, daysSinceLatestCommit=${daysSinceLatestCommit})"
    }
    val daysSinceLatestCommit by lazy {
        require(latestCommit != null) { "latestCommit must be set" }
        val commitDate = LocalDate.parse(latestCommit!!.commit.author.date, DateTimeFormatter.ISO_DATE_TIME)
        ChronoUnit.DAYS.between(commitDate, LocalDate.now())
    }


}

fun generateLogReport(repositoryInfos: List<RepositoryInfo>) {
    val dependabot = StringBuilder("**Mange dependabots**\n")
    repositoryInfos
        .filter { it.openDependenciesSum > 9 }
        .sortedByDescending { it.openDependenciesSum }
        .forEach { dependabot.appendLine(makeLine(it, it.openDependenciesSum, "/pulls")) }

    val critical = StringBuilder("**Kritisk dependabot security alert:**\n")
    repositoryInfos
        .filter { it.criticalAlertsSum > 0 }
        .sortedByDescending { it.criticalAlertsSum }
        .forEach { critical.appendLine(makeLine(it, it.criticalAlertsSum, "/security/dependabot")) }

    val secret = StringBuilder("**Secrets alerts:**\n")
    repositoryInfos
        .filter { it.secretAlerts > 0 }
        .sortedByDescending { it.secretAlerts }
        .forEach { secret.appendLine(makeLine(it, it.secretAlerts, "/security/secret-scanning")) }

    val codescanning = StringBuilder("**Kritiske kodescanning varsler:**\n")
    repositoryInfos
        .filter { it.codeScanningCriticalAlerts > 0 }
        .sortedByDescending { it.codeScanningCriticalAlerts }
        .forEach { codescanning.appendLine(makeLine(it, it.codeScanningCriticalAlerts, "/security/code-scanning")) }

    val oldCommits = StringBuilder("**Repoer med commits eldre enn 50 dager:**\n")
    repositoryInfos
        .filter { it.daysSinceLatestCommit > 50 }
        .sortedByDescending { it.daysSinceLatestCommit }
        .forEach { oldCommits.appendLine(makeLine(it, it.daysSinceLatestCommit.toInt(), "")) }

    logger.info("Rapport:")
    logger.info("$dependabot\n$critical\n$codescanning\n$secret\n$oldCommits")
}

private fun makeLine(repo: RepositoryInfo, amount: Int, githubPostfix: String): String {
    return "- [${repo.repository}](https://github.com/navikt/${repo.repository}$githubPostfix) $amount"
}


@Serializable
data class PullRequest(
    val updated_at: String,
    val title: String,
    val user: User
)

@Serializable
data class User(
    val login: String,
    val type: String

)

@Serializable
data class DependabotAlert(
    val security_vulnerability: SecurityVulnerability,
)

@Serializable
class SecretAlert()

@Serializable
class CodescanningAlert()


@Serializable
data class SecurityVulnerability(
    val severity: String
)

@Serializable
data class OrgRepository(
    val name: String,
    val archived: Boolean,
    val visibility: String,
    val permissions: Permissions
)

@Serializable
data class Permissions(
    val admin: Boolean,
    val maintain: Boolean,
    val push: Boolean,
    val triage: Boolean,
    val pull: Boolean
)

@Serializable
data class Commit(
    val commit: CommitDetails
)

@Serializable
data class CommitDetails(
    val author: CommitAuthor
)

@Serializable
data class CommitAuthor(
    val date: String
)