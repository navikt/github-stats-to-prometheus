package no.nav.github_stats

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val config = Config.fromEnv()
    val token = resolveToken(config.authMode, buildHttpClient(), config.githubApiUrl, config.githubApiVersion)
    val client = GitHubClient(buildHttpClient(config.githubApiVersion, token), config.githubApiUrl, config.githubOrg)
    val metrics = MetricsRegistry()
    val stopTimer = metrics.startTimer()

    try {
        runBlocking {
            config.githubTeams.forEach { team ->
                log.info("Processing team '$team'")
                client.teamRepos(team)
                    .filter { !it.archived && it.name !in config.excludedRepos }
                    .filter { roleFromPermissions(it.permissions).meetsMinimum(config.minimumRole) }
                    .also { log.info("Team '$team': ${it.size} repos after filtering") }
                    .forEach { repo ->
                        fetchRepoMetrics(client, repo.name)
                            .also { log.info("Metrics for '${repo.name}': $it") }
                            .also { metrics.record(config.githubOrg, team, it) }
                    }
            }
        }
    } finally {
        stopTimer()
    }

    metrics.push(config.pushGatewayAddress, config.githubOrg)
    log.info("Finished successfully")
}

private fun buildHttpClient(apiVersion: String? = null, bearerToken: String? = null): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { isLenient = true; ignoreUnknownKeys = true }) }
    install(HttpRequestRetry) {
        maxRetries = 3
        retryOnServerErrors(maxRetries = 3)
        retryOnException(maxRetries = 3, retryOnTimeout = true)
        exponentialDelay(base = 2.0, maxDelayMs = 16_000L)
        modifyRequest { if (retryCount > 0) log.warn("Retry $retryCount for ${it.url}") }
    }
    bearerToken?.let {
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $it")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            apiVersion?.let { v -> header("X-GitHub-Api-Version", v) }
            contentType(ContentType.Application.Json)
        }
    }
}

private suspend fun fetchRepoMetrics(client: GitHubClient, repo: String): RepoMetrics {
    val pullRequests = client.openPullRequests(repo)
    return RepoMetrics(
        repository = repo,
        openPRs = pullRequests.size,
        dependabotPrs = pullRequests.filter { it.user.login == "dependabot[bot]" },
        dependabotAlerts = client.dependabotAlerts(repo),
        secretAlerts = client.secretAlerts(repo),
        codeScanningCriticalAlerts = client.codeScanningAlerts(repo),
        latestCommit = client.latestCommit(repo)
    )
}

data class RepoMetrics(
    val repository: String,
    val openPRs: Int,
    val dependabotPrs: List<PullRequest>,
    val dependabotAlerts: List<DependabotAlert> = emptyList(),
    val secretAlerts: Int = 0,
    val codeScanningCriticalAlerts: Int = 0,
    val latestCommit: Commit? = null
) {
    companion object {
        private val groupUpdatesRegex = "(\\d+)\\s+updates?$".toRegex()
        private val groupTitleRegex = ".+group.+directory.+updates?$".toRegex()
    }

    val openDependenciesSum: Int by lazy {
        val titles = dependabotPrs.map { it.title }.toSet()
        val groupTitles = titles.filter { groupTitleRegex.matches(it) }.toSet()
        titles.minus(groupTitles).size + groupTitles.sumOf {
            groupUpdatesRegex.find(it)?.groups?.lastOrNull()?.value?.toInt() ?: 0
        }
    }

    val criticalAlertsSum: Int by lazy { dependabotAlerts.count { it.security_vulnerability.severity == "critical" } }
    val highAlertsSum: Int by lazy { dependabotAlerts.count { it.security_vulnerability.severity == "high" } }
    val daysSinceLatestCommit: Long? by lazy {
        latestCommit?.let {
            ChronoUnit.DAYS.between(
                LocalDate.parse(
                    it.commit.author.date,
                    DateTimeFormatter.ISO_DATE_TIME
                ), LocalDate.now()
            )
        }
    }

    override fun toString() =
        "RepoMetrics(repo='$repository', openPRs=$openPRs, dependencyUpdates=$openDependenciesSum, " +
                "criticalAlerts=$criticalAlertsSum, highAlerts=$highAlertsSum, secretAlerts=$secretAlerts, " +
                "codeScanningCritical=$codeScanningCriticalAlerts, daysSinceLastCommit=$daysSinceLatestCommit)"
}
