package no.nav.github_stats

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("GitHubGraphQLClient")
private const val BATCH_SIZE = 20

class GitHubGraphQLClient(
    private val httpClient: HttpClient,
    private val graphqlUrl: String,
    private val org: String,
) {
    suspend fun fetchRepoData(repos: List<String>): Map<String, GraphQLRepoData> {
        val result = mutableMapOf<String, GraphQLRepoData>()
        repos.chunked(BATCH_SIZE).forEach { batch ->
            result.putAll(fetchBatch(batch))
        }
        return result
    }

    private suspend fun fetchBatch(repos: List<String>): Map<String, GraphQLRepoData> {
        val query = buildBatchQuery(repos)
        val response =
            try {
                httpClient.post(graphqlUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(JsonObject(mapOf("query" to JsonPrimitive(query))))
                }
            } catch (e: Exception) {
                log.warn("GraphQL batch request failed: ${e.message}")
                return emptyMap()
            }

        if (!response.status.isSuccess()) {
            log.warn("GraphQL batch returned HTTP ${response.status}")
            return emptyMap()
        }

        val body =
            try {
                response.body<JsonObject>()
            } catch (e: Exception) {
                log.warn("Failed to parse GraphQL response: ${e.message}")
                return emptyMap()
            }

        body["errors"]?.jsonArray?.forEach { log.warn("GraphQL error: $it") }

        val data = body["data"]?.jsonObject ?: return emptyMap()
        val result = mutableMapOf<String, GraphQLRepoData>()

        repos.forEachIndexed { index, repo ->
            val alias = "repo$index"
            val repoData = data[alias]
            if (repoData == null || repoData is JsonNull) {
                log.warn("No GraphQL data returned for repo '$repo'")
                return@forEachIndexed
            }
            try {
                result[repo] = parseRepoData(repo, repoData.jsonObject)
            } catch (e: Exception) {
                log.warn("Failed to parse GraphQL data for repo '$repo': ${e.message}")
            }
        }
        return result
    }

    private suspend fun parseRepoData(
        repo: String,
        data: JsonObject,
    ): GraphQLRepoData {
        val prConnection = data["pullRequests"]?.jsonObject
        val prs = parsePullRequests(prConnection)
        val allPrs =
            if (prConnection
                    ?.get("pageInfo")
                    ?.jsonObject
                    ?.get("hasNextPage")
                    ?.jsonPrimitive
                    ?.boolean == true
            ) {
                val cursor =
                    prConnection["pageInfo"]
                        ?.jsonObject
                        ?.get("endCursor")
                        ?.jsonPrimitive
                        ?.content
                prs + fetchRemainingPullRequests(repo, cursor)
            } else {
                prs
            }

        val alertConnection = data["vulnerabilityAlerts"]?.jsonObject
        val alerts = parseVulnerabilityAlerts(alertConnection)
        val allAlerts =
            if (alertConnection
                    ?.get("pageInfo")
                    ?.jsonObject
                    ?.get("hasNextPage")
                    ?.jsonPrimitive
                    ?.boolean == true
            ) {
                val cursor =
                    alertConnection["pageInfo"]
                        ?.jsonObject
                        ?.get("endCursor")
                        ?.jsonPrimitive
                        ?.content
                alerts + fetchRemainingVulnerabilityAlerts(repo, cursor)
            } else {
                alerts
            }

        val committedDate =
            data["defaultBranchRef"]
                ?.takeIf { it !is JsonNull }
                ?.jsonObject
                ?.get("target")
                ?.takeIf { it !is JsonNull }
                ?.jsonObject
                ?.get("committedDate")
                ?.jsonPrimitive
                ?.contentOrNull

        return GraphQLRepoData(pullRequests = allPrs, vulnerabilityAlerts = allAlerts, latestCommitDate = committedDate)
    }

    private suspend fun fetchRemainingPullRequests(
        repo: String,
        afterCursor: String?,
    ): List<GraphQLPullRequest> {
        val all = mutableListOf<GraphQLPullRequest>()
        var cursor = afterCursor
        while (cursor != null) {
            val query =
                """
                query {
                  repository(owner: "$org", name: "$repo") {
                    pullRequests(states: OPEN, first: 100, after: "$cursor") {
                      nodes { title author { login } }
                      pageInfo { hasNextPage endCursor }
                    }
                  }
                }
                """.trimIndent()
            val data = executeQuery(query, repo) ?: break
            val conn = data["pullRequests"]?.jsonObject ?: break
            all.addAll(parsePullRequests(conn))
            cursor =
                if (conn["pageInfo"]
                        ?.jsonObject
                        ?.get("hasNextPage")
                        ?.jsonPrimitive
                        ?.boolean == true
                ) {
                    conn["pageInfo"]
                        ?.jsonObject
                        ?.get("endCursor")
                        ?.jsonPrimitive
                        ?.contentOrNull
                } else {
                    null
                }
        }
        return all
    }

    private suspend fun fetchRemainingVulnerabilityAlerts(
        repo: String,
        afterCursor: String?,
    ): List<GraphQLVulnerabilityAlert> {
        val all = mutableListOf<GraphQLVulnerabilityAlert>()
        var cursor = afterCursor
        while (cursor != null) {
            val query =
                """
                query {
                  repository(owner: "$org", name: "$repo") {
                    vulnerabilityAlerts(states: OPEN, first: 100, after: "$cursor") {
                      nodes { securityVulnerability { severity } }
                      pageInfo { hasNextPage endCursor }
                    }
                  }
                }
                """.trimIndent()
            val data = executeQuery(query, repo) ?: break
            val conn = data["vulnerabilityAlerts"]?.jsonObject ?: break
            all.addAll(parseVulnerabilityAlerts(conn))
            cursor =
                if (conn["pageInfo"]
                        ?.jsonObject
                        ?.get("hasNextPage")
                        ?.jsonPrimitive
                        ?.boolean == true
                ) {
                    conn["pageInfo"]
                        ?.jsonObject
                        ?.get("endCursor")
                        ?.jsonPrimitive
                        ?.contentOrNull
                } else {
                    null
                }
        }
        return all
    }

    private suspend fun executeQuery(
        query: String,
        repo: String,
    ): JsonObject? {
        return try {
            val response =
                httpClient.post(graphqlUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(JsonObject(mapOf("query" to JsonPrimitive(query))))
                }
            if (!response.status.isSuccess()) {
                log.warn("GraphQL follow-up request failed for '$repo': HTTP ${response.status}")
                return null
            }
            val body = response.body<JsonObject>()
            body["errors"]?.jsonArray?.forEach { log.warn("GraphQL error for '$repo': $it") }
            body["data"]?.jsonObject?.get("repository")?.jsonObject
        } catch (e: Exception) {
            log.warn("GraphQL follow-up request failed for '$repo': ${e.message}")
            null
        }
    }

    private fun parsePullRequests(connection: JsonObject?): List<GraphQLPullRequest> =
        connection?.get("nodes")?.jsonArray?.mapNotNull { node ->
            try {
                val obj = node.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val login =
                    obj["author"]
                        ?.takeIf { it !is JsonNull }
                        ?.jsonObject
                        ?.get("login")
                        ?.jsonPrimitive
                        ?.contentOrNull
                GraphQLPullRequest(title = title, authorLogin = login)
            } catch (e: Exception) {
                log.warn("Failed to parse pull request node: ${e.message}")
                null
            }
        } ?: emptyList()

    private fun parseVulnerabilityAlerts(connection: JsonObject?): List<GraphQLVulnerabilityAlert> =
        connection?.get("nodes")?.jsonArray?.mapNotNull { node ->
            try {
                val severity =
                    node.jsonObject["securityVulnerability"]
                        ?.jsonObject
                        ?.get("severity")
                        ?.jsonPrimitive
                        ?.contentOrNull
                GraphQLVulnerabilityAlert(severity = severity?.lowercase())
            } catch (e: Exception) {
                log.warn("Failed to parse vulnerability alert node: ${e.message}")
                null
            }
        } ?: emptyList()

    private fun buildBatchQuery(repos: List<String>): String {
        val fragments =
            repos
                .mapIndexed { index, repo ->
                    """
                    repo$index: repository(owner: "$org", name: "$repo") {
                      pullRequests(states: OPEN, first: 100) {
                        nodes { title author { login } }
                        pageInfo { hasNextPage endCursor }
                      }
                      vulnerabilityAlerts(states: OPEN, first: 100) {
                        nodes { securityVulnerability { severity } }
                        pageInfo { hasNextPage endCursor }
                      }
                      defaultBranchRef {
                        target { ... on Commit { committedDate } }
                      }
                    }
                    """.trimIndent()
                }.joinToString("\n")
        return "query {\n$fragments\n}"
    }
}

data class GraphQLRepoData(
    val pullRequests: List<GraphQLPullRequest>,
    val vulnerabilityAlerts: List<GraphQLVulnerabilityAlert>,
    val latestCommitDate: String?,
)

data class GraphQLPullRequest(
    val title: String,
    val authorLogin: String?,
)

data class GraphQLVulnerabilityAlert(
    val severity: String?,
)
