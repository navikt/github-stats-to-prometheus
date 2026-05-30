package no.nav.github_stats

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class GitHubGraphQLClientTest {
    private fun mockClient(handler: MockRequestHandleScope.(request: HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine { handler(it) }) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
        }

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData {
        val headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
        return respond(body, status, headers)
    }

    private fun client(handler: MockRequestHandleScope.(request: HttpRequestData) -> HttpResponseData) =
        GitHubGraphQLClient(mockClient(handler), "https://api.github.com/graphql", "my-org")

    private fun graphqlResponse(data: String) = """{"data":{$data}}"""

    private fun repoData(
        prs: String = "[]",
        alerts: String = "[]",
        committedDate: String? = "2024-06-01T12:00:00Z",
    ) = """
        {
          "pullRequests": {
            "nodes": $prs,
            "pageInfo": {"hasNextPage": false, "endCursor": null}
          },
          "vulnerabilityAlerts": {
            "nodes": $alerts,
            "pageInfo": {"hasNextPage": false, "endCursor": null}
          },
          "defaultBranchRef": ${if (committedDate != null) """{"target": {"committedDate": "$committedDate"}}""" else "null"}
        }
        """.trimIndent()

    private fun repoFragment(
        alias: String,
        prs: String = "[]",
        alerts: String = "[]",
        committedDate: String? = "2024-06-01T12:00:00Z",
    ) = """"$alias": ${repoData(prs, alerts, committedDate)}"""

    @Test
    fun `returns parsed PRs and alerts for a single repo`() =
        runBlocking {
            val c =
                client {
                    jsonResponse(
                        graphqlResponse(
                            repoFragment(
                                alias = "repo0",
                                prs = """[{"title":"Fix bug","author":{"login":"octocat"}},{"title":"Bump dep","author":{"login":"dependabot"}}]""",
                                alerts = """[{"securityVulnerability":{"severity":"CRITICAL"}},{"securityVulnerability":{"severity":"HIGH"}}]""",
                            ),
                        ),
                    )
                }
            val result = c.fetchRepoData(listOf("repo-a"))
            val data = result["repo-a"]
            assertNotNull(data)
            assertEquals(2, data.pullRequests.size)
            assertEquals("dependabot", data.pullRequests[1].authorLogin)
            assertEquals(2, data.vulnerabilityAlerts.size)
            assertEquals("critical", data.vulnerabilityAlerts[0].severity)
            assertEquals("2024-06-01T12:00:00Z", data.latestCommitDate)
        }

    @Test
    fun `handles null author on PR`() =
        runBlocking {
            val c =
                client {
                    jsonResponse(
                        graphqlResponse(
                            repoFragment(
                                alias = "repo0",
                                prs = """[{"title":"Fix bug","author":null}]""",
                            ),
                        ),
                    )
                }
            val result = c.fetchRepoData(listOf("repo-a"))
            val pr = result["repo-a"]?.pullRequests?.first()
            assertNotNull(pr)
            assertNull(pr.authorLogin)
        }

    @Test
    fun `handles null defaultBranchRef`() =
        runBlocking {
            val c =
                client {
                    jsonResponse(graphqlResponse(repoFragment(alias = "repo0", committedDate = null)))
                }
            assertNull(c.fetchRepoData(listOf("repo-a"))["repo-a"]?.latestCommitDate)
        }

    @Test
    fun `returns empty map on HTTP error`() =
        runBlocking {
            val c = client { jsonResponse("{}", HttpStatusCode.InternalServerError) }
            assertTrue(c.fetchRepoData(listOf("repo-a")).isEmpty())
        }

    @Test
    fun `returns empty map on malformed JSON`() =
        runBlocking {
            val c = client { jsonResponse("not json") }
            assertTrue(c.fetchRepoData(listOf("repo-a")).isEmpty())
        }

    @Test
    fun `skips repo with null data and logs warning`() =
        runBlocking {
            val c =
                client {
                    jsonResponse(graphqlResponse(""""repo0": null"""))
                }
            assertTrue(c.fetchRepoData(listOf("repo-a")).isEmpty())
        }

    @Test
    fun `batches repos into groups of 20`() =
        runBlocking {
            var requestCount = 0
            val repos = (1..25).map { "repo-$it" }
            val batchSizes = listOf(20, 5)
            val c =
                client {
                    val size = batchSizes[requestCount]
                    requestCount++
                    val aliases = (0 until size).joinToString(",") { i -> repoFragment("repo$i") }
                    jsonResponse(graphqlResponse(aliases))
                }
            val result = c.fetchRepoData(repos)
            assertEquals(2, requestCount)
            assertEquals(25, result.size)
        }

    @Test
    fun `follows pagination for PRs`() =
        runBlocking {
            var requestCount = 0
            val c =
                client {
                    requestCount++
                    if (requestCount == 1) {
                        jsonResponse(
                            graphqlResponse(
                                """"repo0": {
                  "pullRequests": {
                    "nodes": [{"title":"PR 1","author":{"login":"octocat"}}],
                    "pageInfo": {"hasNextPage": true, "endCursor": "cursor123"}
                  },
                  "vulnerabilityAlerts": {"nodes": [], "pageInfo": {"hasNextPage": false, "endCursor": null}},
                  "defaultBranchRef": null
                }""",
                            ),
                        )
                    } else {
                        jsonResponse(
                            """{"data":{"repository":{"pullRequests":{"nodes":[{"title":"PR 2","author":{"login":"octocat"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""",
                        )
                    }
                }
            val result = c.fetchRepoData(listOf("repo-a"))
            assertEquals(2, result["repo-a"]?.pullRequests?.size)
        }

    @Test
    fun `graphql errors are logged and partial data is returned`() =
        runBlocking {
            val c =
                client {
                    jsonResponse("""{"data":{${repoFragment("repo0")}},"errors":[{"message":"some error"}]}""")
                }
            val result = c.fetchRepoData(listOf("repo-a"))
            assertEquals(1, result.size)
        }
}
