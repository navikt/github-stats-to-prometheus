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
    val token = resolveToken(config, buildHttpClient())
    val restClient =
        GitHubClient(buildHttpClient(config.githubApiVersion, token), config.githubApiUrl, config.githubOrg)
    val graphqlClient =
        GitHubGraphQLClient(buildHttpClient(config.githubApiVersion, token), config.githubGraphqlUrl, config.githubOrg)
    val metrics = MetricsRegistry()

    runBlocking {
        config.githubTeams.forEach { team ->
            log.info("Processing team '$team'")
            val repos =
                restClient
                    .teamRepos(team)
                    .filter { !it.archived && it.name !in config.excludedRepos }
                    .filter { roleFromPermissions(it.permissions).meetsMinimum(config.minimumRole) }
                    .also { log.info("Team '$team': ${it.size} repos after filtering") }

            val repoNames = repos.map { it.name }
            val graphqlData = graphqlClient.fetchRepoData(repoNames)
            val restData = restClient.secretAndCodeScanningAlerts(repoNames)

            repos.forEach { repo ->
                val gql = graphqlData[repo.name] ?: GraphQLRepoData(emptyList(), emptyList(), null)
                val (secretAlerts, codeScanningAlerts) = restData[repo.name] ?: Pair(0, emptyList())
                RepoMetrics(
                    repository = repo.name,
                    pullRequests = gql.pullRequests,
                    vulnerabilityAlerts = gql.vulnerabilityAlerts,
                    secretAlerts = secretAlerts,
                    codeScanningAlerts = codeScanningAlerts,
                    latestCommitDate = gql.latestCommitDate,
                ).also { log.info("Metrics for '${repo.name}': $it") }
                    .also { metrics.record(config.githubOrg, team, it) }
            }
        }
    }

    metrics.push(config.pushGatewayAddress, config.githubOrg)
    log.info("Finished successfully")
}

private fun buildHttpClient(
    apiVersion: String? = null,
    bearerToken: String? = null,
): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    isLenient = true
                    ignoreUnknownKeys = true
                },
            )
        }
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

data class RepoMetrics(
    val repository: String,
    val pullRequests: List<GraphQLPullRequest> = emptyList(),
    val vulnerabilityAlerts: List<GraphQLVulnerabilityAlert> = emptyList(),
    val secretAlerts: Int = 0,
    val codeScanningAlerts: List<CodescanningAlert> = emptyList(),
    val latestCommitDate: String? = null,
) {
    val openPRs: Int by lazy { pullRequests.size }
    val openDependenciesSum: Int by lazy {
        pullRequests
            .filter { it.authorLogin == "dependabot[bot]" }
            .map { it.title }
            .toSet()
            .size
    }
    val dependabotCritical: Int by lazy { vulnerabilityAlerts.count { it.severity == "critical" } }
    val dependabotHigh: Int by lazy { vulnerabilityAlerts.count { it.severity == "high" } }
    val dependabotTotal: Int by lazy { vulnerabilityAlerts.size }
    val codeScanningCritical: Int by lazy { codeScanningAlerts.count { it.rule.security_severity_level == "critical" } }
    val codeScanningTotal: Int by lazy { codeScanningAlerts.size }
    val daysSinceLatestCommit: Long? by lazy {
        latestCommitDate?.let {
            ChronoUnit.DAYS.between(
                LocalDate.parse(it, DateTimeFormatter.ISO_DATE_TIME),
                LocalDate.now(),
            )
        }
    }

    override fun toString() =
        "RepoMetrics(repo='$repository', openPRs=$openPRs, dependencyUpdates=$openDependenciesSum, " +
                "dependabotCritical=$dependabotCritical, dependabotHigh=$dependabotHigh, dependabotTotal=$dependabotTotal, " +
                "secretAlerts=$secretAlerts, codeScanningCritical=$codeScanningCritical, codeScanningTotal=$codeScanningTotal, " +
                "daysSinceLastCommit=$daysSinceLatestCommit)"
}
