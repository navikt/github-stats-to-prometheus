package no.nav.github_stats

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("GitHubClient")

class GitHubClient(private val httpClient: HttpClient, private val apiUrl: String, val org: String) {

    suspend fun teamRepos(team: String): List<OrgRepository> =
        fetchAllPages("${apiUrl}orgs/$org/teams/$team/repos") { it.body<List<OrgRepository>>() }
            .also { log.info("Found ${it.size} repositories for team '$team'") }

    suspend fun openPullRequests(repo: String): List<PullRequest> =
        safeFetch(repo, "pull requests") {
            fetchAllPages("${apiUrl}repos/$org/$repo/pulls") { it.body<List<PullRequest>>() }
        } ?: emptyList()

    suspend fun dependabotAlerts(repo: String): List<DependabotAlert> =
        safeFetch(repo, "dependabot alerts") {
            fetchAllPages("${apiUrl}repos/$org/$repo/dependabot/alerts") { it.body<List<DependabotAlert>>() }
        } ?: emptyList()

    suspend fun latestCommit(repo: String): Commit? =
        safeFetch(repo, "latest commit") {
            val response = httpClient.get("${apiUrl}repos/$org/$repo/commits") { parameter("per_page", "1") }
            if (!response.status.isSuccess()) { log.warn("HTTP ${response.status} fetching latest commit for '$repo'"); return@safeFetch null }
            response.body<List<Commit>>().firstOrNull()
        }

    suspend fun secretAlerts(repo: String): Int =
        safeFetch(repo, "secret scanning alerts") {
            fetchAllPages("${apiUrl}repos/$org/$repo/secret-scanning/alerts") { it.body<List<SecretAlert>>() }.size
        } ?: 0

    suspend fun codeScanningAlerts(repo: String): Int =
        safeFetch(repo, "code scanning alerts") {
            fetchAllPages("${apiUrl}repos/$org/$repo/code-scanning/alerts") { it.body<List<CodescanningAlert>>() }.size
        } ?: 0

    private suspend fun <T> fetchAllPages(firstUrl: String, parse: suspend (HttpResponse) -> List<T>): List<T> {
        val results = mutableListOf<T>()
        var nextUrl: String? = firstUrl
        while (nextUrl != null) {
            val response = httpClient.get(nextUrl) { parameter("per_page", "100"); parameter("state", "open") }
            if (!response.status.isSuccess()) { log.warn("HTTP ${response.status} fetching '$nextUrl'"); break }
            results.addAll(parse(response))
            nextUrl = response.headers["Link"]
                ?.split(",")?.map { it.trim() }?.firstOrNull { it.contains("rel=\"next\"") }
                ?.let { "<([^>]+)>".toRegex().find(it)?.groupValues?.get(1) }
        }
        return results
    }

    private suspend fun <T> safeFetch(repo: String, what: String, block: suspend () -> T): T? =
        try { block() } catch (e: Exception) { log.warn("Failed to fetch $what for '$repo': ${e.message}"); null }
}

@Serializable data class OrgRepository(val name: String, val archived: Boolean, val visibility: String, val permissions: Permissions)
@Serializable data class Permissions(val admin: Boolean, val maintain: Boolean, val push: Boolean, val triage: Boolean, val pull: Boolean)
@Serializable data class PullRequest(val updated_at: String, val title: String, val user: User)
@Serializable data class User(val login: String, val type: String)
@Serializable data class DependabotAlert(val security_vulnerability: SecurityVulnerability)
@Serializable data class SecurityVulnerability(val severity: String)
@Serializable class SecretAlert
@Serializable class CodescanningAlert
@Serializable data class Commit(val commit: CommitDetails)
@Serializable data class CommitDetails(val author: CommitAuthor)
@Serializable data class CommitAuthor(val date: String)
