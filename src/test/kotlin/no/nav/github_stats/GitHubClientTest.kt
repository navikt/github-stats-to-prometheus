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
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: Headers = headersOf()
    ): HttpResponseData {
        val combined = Headers.build {
            appendAll(headers)
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        return respond(body, status, combined)
    }

    private fun client(handler: MockRequestHandleScope.(request: HttpRequestData) -> HttpResponseData) =
        GitHubClient(mockClient(handler), "https://api.github.com/", "my-org")

    @Test
    fun `teamRepos returns parsed repositories`() = runBlocking {
        val c =
            client { jsonResponse("""[{"name":"repo-a","archived":false,"visibility":"public","permissions":{"admin":false,"maintain":false,"push":true,"triage":false,"pull":true}}]""") }
        val repos = c.teamRepos("team-x")
        assertEquals(1, repos.size)
        assertEquals("repo-a", repos[0].name)
        assertFalse(repos[0].archived)
        assertTrue(repos[0].permissions.push)
    }

    @Test
    fun `teamRepos returns empty list on 404`() = runBlocking {
        val c = client { jsonResponse("", HttpStatusCode.NotFound) }
        assertEquals(emptyList(), c.teamRepos("ghost-team"))
    }

    @Test
    fun `teamRepos returns empty list on 403 forbidden`() = runBlocking {
        val c = client { jsonResponse("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden) }
        assertEquals(emptyList(), c.teamRepos("team-x"))
    }

    @Test
    fun `teamRepos paginates via Link header`() = runBlocking {
        var page = 0
        val c = client {
            page++
            when (page) {
                1 -> jsonResponse(
                    """[{"name":"repo-a","archived":false,"visibility":"public","permissions":{"admin":false,"maintain":false,"push":true,"triage":false,"pull":true}}]""",
                    headers = headersOf(
                        "Link",
                        """<https://api.github.com/orgs/my-org/teams/team-x/repos?page=2>; rel="next""""
                    )
                )

                else -> jsonResponse(
                    """[{"name":"repo-b","archived":false,"visibility":"public","permissions":{"admin":true,"maintain":false,"push":true,"triage":false,"pull":true}}]"""
                )
            }
        }
        val repos = c.teamRepos("team-x")
        assertEquals(2, repos.size)
        assertEquals(listOf("repo-a", "repo-b"), repos.map { it.name })
    }

    @Test
    fun `teamRepos stops pagination on 500`() = runBlocking {
        var page = 0
        val c = client {
            page++
            when (page) {
                1 -> jsonResponse(
                    """[{"name":"repo-a","archived":false,"visibility":"public","permissions":{"admin":false,"maintain":false,"push":true,"triage":false,"pull":true}}]""",
                    headers = headersOf(
                        "Link",
                        """<https://api.github.com/orgs/my-org/teams/team-x/repos?page=2>; rel="next""""
                    )
                )

                else -> jsonResponse("Internal Server Error", HttpStatusCode.InternalServerError)
            }
        }
        val repos = c.teamRepos("team-x")
        assertEquals(1, repos.size, "Should return only the first page before the error")
    }

    @Test
    fun `openPullRequests returns parsed PRs`() = runBlocking {
        val c =
            client { jsonResponse("""[{"updated_at":"2024-01-01T00:00:00Z","title":"Fix bug","user":{"login":"octocat","type":"User"}},{"updated_at":"2024-01-02T00:00:00Z","title":"Bump dep","user":{"login":"dependabot[bot]","type":"Bot"}}]""") }
        val prs = c.openPullRequests("repo-a")
        assertEquals(2, prs.size)
        assertEquals("dependabot[bot]", prs[1].user.login)
    }

    @Test
    fun `openPullRequests returns empty list on 403 rate limit`() = runBlocking {
        val c = client { jsonResponse("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden) }
        assertEquals(emptyList(), c.openPullRequests("repo-a"))
    }

    @Test
    fun `openPullRequests returns empty list on 429 too many requests`() = runBlocking {
        val c = client { jsonResponse("""{"message":"Too many requests"}""", HttpStatusCode(429, "Too Many Requests")) }
        assertEquals(emptyList(), c.openPullRequests("repo-a"))
    }

    @Test
    fun `openPullRequests returns empty list on malformed JSON`() = runBlocking {
        val c = client { jsonResponse("not json at all") }
        assertEquals(emptyList(), c.openPullRequests("repo-a"))
    }

    @Test
    fun `openPullRequests returns empty list on unexpected JSON shape`() = runBlocking {
        val c = client { jsonResponse("""{"unexpected":"object"}""") }
        assertEquals(emptyList(), c.openPullRequests("repo-a"))
    }

    @Test
    fun `dependabotAlerts parses severity correctly`() = runBlocking {
        val c =
            client { jsonResponse("""[{"security_vulnerability":{"severity":"critical"}},{"security_vulnerability":{"severity":"high"}},{"security_vulnerability":{"severity":"medium"}}]""") }
        val alerts = c.dependabotAlerts("repo-a")
        assertEquals(3, alerts.size)
        assertEquals("critical", alerts[0].security_vulnerability.severity)
    }

    @Test
    fun `dependabotAlerts returns empty list on 404`() = runBlocking {
        val c = client { jsonResponse("", HttpStatusCode.NotFound) }
        assertEquals(emptyList(), c.dependabotAlerts("repo-a"))
    }

    @Test
    fun `latestCommit returns parsed commit`() = runBlocking {
        val c = client { jsonResponse("""[{"commit":{"author":{"date":"2024-06-01T12:00:00Z"}}}]""") }
        val commit = c.latestCommit("repo-a")
        assertNotNull(commit)
        assertEquals("2024-06-01T12:00:00Z", commit.commit.author.date)
    }

    @Test
    fun `latestCommit returns null on empty repo`() = runBlocking {
        val c = client { jsonResponse("[]") }
        assertNull(c.latestCommit("repo-a"))
    }

    @Test
    fun `latestCommit returns null on 409 conflict (empty git repo)`() = runBlocking {
        val c = client { jsonResponse("""{"message":"Git Repository is empty."}""", HttpStatusCode.Conflict) }
        assertNull(c.latestCommit("repo-a"))
    }

    @Test
    fun `latestCommit returns null on 500`() = runBlocking {
        val c = client { jsonResponse("error", HttpStatusCode.InternalServerError) }
        assertNull(c.latestCommit("repo-a"))
    }

    @Test
    fun `secretAlerts returns count`() = runBlocking {
        val c = client { jsonResponse("""[{},{}]""") }
        assertEquals(2, c.secretAlerts("repo-a"))
    }

    @Test
    fun `secretAlerts returns 0 on 404`() = runBlocking {
        val c = client { jsonResponse("", HttpStatusCode.NotFound) }
        assertEquals(0, c.secretAlerts("repo-a"))
    }

    @Test
    fun `codeScanningAlerts returns count`() = runBlocking {
        val c = client { jsonResponse("""[{},{},{}]""") }
        assertEquals(3, c.codeScanningAlerts("repo-a"))
    }

    @Test
    fun `codeScanningAlerts returns 0 on 403 (feature not enabled)`() = runBlocking {
        val c = client { jsonResponse("""{"message":"Advanced Security must be enabled"}""", HttpStatusCode.Forbidden) }
        assertEquals(0, c.codeScanningAlerts("repo-a"))
    }

    @Test
    fun `ignores unknown fields in JSON response`() = runBlocking {
        val c =
            client { jsonResponse("""[{"name":"repo-a","archived":false,"visibility":"public","permissions":{"admin":false,"maintain":false,"push":true,"triage":false,"pull":true},"extra_future_field":"ignored"}]""") }
        val repos = c.teamRepos("team-x")
        assertEquals(1, repos.size)
    }
}
