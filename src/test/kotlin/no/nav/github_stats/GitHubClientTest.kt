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

class GitHubClientTest {
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
        headers: Headers = headersOf(),
    ): HttpResponseData {
        val combined =
            Headers.build {
                appendAll(headers)
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        return respond(body, status, combined)
    }

    private fun client(handler: MockRequestHandleScope.(request: HttpRequestData) -> HttpResponseData) =
        GitHubClient(mockClient(handler), "https://api.github.com/", "my-org")

    @Test fun `teamRepos returns parsed repositories`() =
        runBlocking {
            val c =
                client {
                    jsonResponse(
                        """[{"name":"repo-a","archived":false,"visibility":"public","permissions":{"admin":false,"maintain":false,"push":true,"triage":false,"pull":true}}]""",
                    )
                }
            val repos = c.teamRepos("team-x")
            assertEquals(1, repos.size)
            assertEquals("repo-a", repos[0].name)
            assertFalse(repos[0].archived)
            assertTrue(repos[0].permissions.push)
        }

    @Test fun `teamRepos returns empty list on 404`() =
        runBlocking {
            val c = client { jsonResponse("", HttpStatusCode.NotFound) }
            assertEquals(emptyList(), c.teamRepos("ghost-team"))
        }

    @Test fun `teamRepos returns empty list on 403 forbidden`() =
        runBlocking {
            val c = client { jsonResponse("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden) }
            assertEquals(emptyList(), c.teamRepos("team-x"))
        }

    @Test fun `teamRepos paginates via Link header`() =
        runBlocking {
            var page = 0
            val c =
                client {
                    page++
                    when (page) {
                        1 -> {
                            jsonResponse(
                                """[{"name":"repo-a","archived":false,"visibility":"public","permissions":{"admin":false,"maintain":false,"push":true,"triage":false,"pull":true}}]""",
                                headers =
                                    headersOf(
                                        "Link",
                                        """<https://api.github.com/orgs/my-org/teams/team-x/repos?page=2>; rel="next"""",
                                    ),
                            )
                        }

                        else -> {
                            jsonResponse(
                                """[{"name":"repo-b","archived":false,"visibility":"public","permissions":{"admin":true,"maintain":false,"push":true,"triage":false,"pull":true}}]""",
                            )
                        }
                    }
                }
            val repos = c.teamRepos("team-x")
            assertEquals(2, repos.size)
            assertEquals(listOf("repo-a", "repo-b"), repos.map { it.name })
        }

    @Test fun `teamRepos stops pagination on 500`() =
        runBlocking {
            var page = 0
            val c =
                client {
                    page++
                    when (page) {
                        1 -> {
                            jsonResponse(
                                """[{"name":"repo-a","archived":false,"visibility":"public","permissions":{"admin":false,"maintain":false,"push":true,"triage":false,"pull":true}}]""",
                                headers =
                                    headersOf(
                                        "Link",
                                        """<https://api.github.com/orgs/my-org/teams/team-x/repos?page=2>; rel="next"""",
                                    ),
                            )
                        }

                        else -> {
                            jsonResponse("Internal Server Error", HttpStatusCode.InternalServerError)
                        }
                    }
                }
            val repos = c.teamRepos("team-x")
            assertEquals(1, repos.size)
        }

    @Test fun `secretAndCodeScanningAlerts returns counts for multiple repos`() =
        runBlocking {
            val c =
                client { req ->
                    when {
                        req.url.encodedPath.contains("secret-scanning") -> jsonResponse("""[{},{}]""")

                        req.url.encodedPath.contains(
                            "code-scanning",
                        ) -> jsonResponse("""[{"rule":{"security_severity_level":"critical"}}]""")

                        else -> jsonResponse("[]")
                    }
                }
            val result = c.secretAndCodeScanningAlerts(listOf("repo-a"))
            assertEquals(2, result["repo-a"]?.first)
            assertEquals(1, result["repo-a"]?.second?.size)
        }

    @Test fun `secretAndCodeScanningAlerts returns zeros on 404`() =
        runBlocking {
            val c = client { jsonResponse("", HttpStatusCode.NotFound) }
            val result = c.secretAndCodeScanningAlerts(listOf("repo-a"))
            assertEquals(0, result["repo-a"]?.first)
            assertEquals(emptyList(), result["repo-a"]?.second)
        }

    @Test fun `secretAndCodeScanningAlerts returns zeros on 403`() =
        runBlocking {
            val c = client { jsonResponse("""{"message":"Advanced Security must be enabled"}""", HttpStatusCode.Forbidden) }
            val result = c.secretAndCodeScanningAlerts(listOf("repo-a"))
            assertEquals(0, result["repo-a"]?.first)
            assertEquals(emptyList(), result["repo-a"]?.second)
        }

    @Test fun `ignores unknown fields in JSON response`() =
        runBlocking {
            val c =
                client {
                    jsonResponse(
                        """[{"name":"repo-a","archived":false,"visibility":"public","permissions":{"admin":false,"maintain":false,"push":true,"triage":false,"pull":true},"extra_future_field":"ignored"}]""",
                    )
                }
            val repos = c.teamRepos("team-x")
            assertEquals(1, repos.size)
        }
}
