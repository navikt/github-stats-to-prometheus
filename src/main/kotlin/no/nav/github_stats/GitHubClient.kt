package no.nav.github_stats

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("GitHubClient")
private const val REST_CONCURRENCY = 20

class GitHubClient(
    private val httpClient: HttpClient,
    private val apiUrl: String,
    val org: String,
) {
    private val semaphore = Semaphore(REST_CONCURRENCY)

    suspend fun teamRepos(team: String): List<OrgRepository> =
        fetchAllPages("${apiUrl}orgs/$org/teams/$team/repos") { it.body<List<OrgRepository>>() }
            .also { log.info("Found ${it.size} repositories for team '$team'") }

    suspend fun secretAndCodeScanningAlerts(repos: List<String>): Map<String, Pair<Int, List<CodescanningAlert>>> =
        coroutineScope {
            repos
                .map { repo ->
                    async {
                        val secret =
                            semaphore.withPermit {
                                safeFetch(repo, "secret scanning alerts") {
                                    fetchAllPages("${apiUrl}repos/$org/$repo/secret-scanning/alerts") { it.body<List<SecretAlert>>() }.size
                                } ?: 0
                            }
                        val codeScanning =
                            semaphore.withPermit {
                                safeFetch(repo, "code scanning alerts") {
                                    fetchAllPages("${apiUrl}repos/$org/$repo/code-scanning/alerts") { it.body<List<CodescanningAlert>>() }
                                } ?: emptyList()
                            }
                        repo to Pair(secret, codeScanning)
                    }
                }.awaitAll()
                .toMap()
        }

    private suspend fun <T> fetchAllPages(
        firstUrl: String,
        parse: suspend (HttpResponse) -> List<T>,
    ): List<T> {
        val results = mutableListOf<T>()
        var nextUrl: String? = firstUrl
        while (nextUrl != null) {
            val response =
                httpClient.get(nextUrl) {
                    parameter("per_page", "100")
                    parameter("state", "open")
                }
            if (!response.status.isSuccess()) {
                log.warn("HTTP ${response.status} fetching '$nextUrl'")
                break
            }
            results.addAll(parse(response))
            nextUrl =
                response.headers["Link"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.firstOrNull { it.contains("rel=\"next\"") }
                    ?.let { "<([^>]+)>".toRegex().find(it)?.groupValues?.get(1) }
        }
        return results
    }

    private suspend fun <T> safeFetch(
        repo: String,
        what: String,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (e: Exception) {
            log.warn("Failed to fetch $what for '$repo': ${e.message}")
            null
        }
}

@Serializable
data class OrgRepository(
    val name: String,
    val archived: Boolean,
    val visibility: String,
    val permissions: Permissions,
)

@Serializable
data class Permissions(
    val admin: Boolean,
    val maintain: Boolean,
    val push: Boolean,
    val triage: Boolean,
    val pull: Boolean,
)

@Serializable
class SecretAlert

@Serializable
class CodescanningAlert(
    val rule: CodescanningRule = CodescanningRule(),
)

@Serializable
data class CodescanningRule(
    val security_severity_level: String? = null,
)
