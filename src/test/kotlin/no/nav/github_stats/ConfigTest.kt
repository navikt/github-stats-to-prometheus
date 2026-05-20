package no.nav.github_stats

import kotlin.test.*

class ConfigTest {

    private val base = mapOf(
        "GITHUB_ORG" to "my-org",
        "GITHUB_TEAMS" to "team-a,team-b",
        "PUSH_GATEWAY_ADDRESS" to "http://pushgateway:9091",
        "GITHUB_PAT" to "ghp_test"
    )

    @Test
    fun `parses valid config with PAT`() {
        val config = Config.fromEnv(base)
        assertEquals("my-org", config.githubOrg)
        assertEquals(listOf("team-a", "team-b"), config.githubTeams)
        assertEquals("http://pushgateway:9091", config.pushGatewayAddress)
        assertEquals(Role.ADMIN, config.minimumRole)
        assertTrue(config.excludedRepos.isEmpty())
        assertEquals(AuthMode.Pat("ghp_test"), config.authMode)
    }

    @Test
    fun `trims trailing slash from API URL and adds one`() {
        val config = Config.fromEnv(base + ("GITHUB_API_URL" to "https://api.github.com"))
        assertEquals("https://api.github.com/", config.githubApiUrl)
    }

    @Test
    fun `defaults API URL to github`() {
        val config = Config.fromEnv(base)
        assertEquals("https://api.github.com/", config.githubApiUrl)
    }

    @Test
    fun `parses MINIMUM_ROLE case-insensitively`() {
        assertEquals(Role.MAINTAIN, Config.fromEnv(base + ("MINIMUM_ROLE" to "maintain")).minimumRole)
        assertEquals(Role.ADMIN, Config.fromEnv(base + ("MINIMUM_ROLE" to "ADMIN")).minimumRole)
        assertEquals(Role.PULL, Config.fromEnv(base + ("MINIMUM_ROLE" to "pull")).minimumRole)
    }

    @Test
    fun `parses EXCLUDED_REPOS`() {
        val config = Config.fromEnv(base + ("EXCLUDED_REPOS" to "repo-a, repo-b , repo-c"))
        assertEquals(setOf("repo-a", "repo-b", "repo-c"), config.excludedRepos)
    }

    @Test
    fun `trims whitespace from team slugs`() {
        val config = Config.fromEnv(base + ("GITHUB_TEAMS" to " team-a , team-b "))
        assertEquals(listOf("team-a", "team-b"), config.githubTeams)
    }

    @Test
    fun `fails fast on missing GITHUB_ORG`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base - "GITHUB_ORG") }
        assertContains(ex.message!!, "GITHUB_ORG")
    }

    @Test
    fun `fails fast on missing GITHUB_TEAMS`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base - "GITHUB_TEAMS") }
        assertContains(ex.message!!, "GITHUB_TEAMS")
    }

    @Test
    fun `fails fast on missing PUSH_GATEWAY_ADDRESS`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base - "PUSH_GATEWAY_ADDRESS") }
        assertContains(ex.message!!, "PUSH_GATEWAY_ADDRESS")
    }

    @Test
    fun `fails fast with no auth configured`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base - "GITHUB_PAT") }
        assertContains(ex.message!!, "Authentication required")
    }

    @Test
    fun `fails fast on invalid MINIMUM_ROLE`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base + ("MINIMUM_ROLE" to "superadmin")) }
        assertContains(ex.message!!, "MINIMUM_ROLE")
    }

    @Test
    fun `accumulates multiple validation errors`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(emptyMap()) }
        assertContains(ex.message!!, "GITHUB_ORG")
        assertContains(ex.message!!, "GITHUB_TEAMS")
        assertContains(ex.message!!, "PUSH_GATEWAY_ADDRESS")
        assertContains(ex.message!!, "Authentication required")
    }

    @Test
    fun `parses GitHub App auth mode`() {
        val env = base - "GITHUB_PAT" + mapOf(
            "GITHUB_APP_ID" to "123456",
            "GITHUB_APP_PRIVATE_KEY" to "base64key==",
            "GITHUB_APP_INSTALLATION_ID" to "78901234"
        )
        val auth = Config.fromEnv(env).authMode
        assertIs<AuthMode.App>(auth)
        assertEquals(123456L, auth.appId)
        assertEquals(78901234L, auth.installationId)
        assertEquals("base64key==", auth.privateKeyBase64)
    }

    @Test
    fun `App auth fails fast when private key is missing`() {
        val env = base - "GITHUB_PAT" + mapOf(
            "GITHUB_APP_ID" to "123456",
            "GITHUB_APP_INSTALLATION_ID" to "78901234"
        )
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(env) }
        assertContains(ex.message!!, "GITHUB_APP_PRIVATE_KEY")
    }

    @Test
    fun `App auth fails fast when installation ID is not numeric`() {
        val env = base - "GITHUB_PAT" + mapOf(
            "GITHUB_APP_ID" to "123456",
            "GITHUB_APP_PRIVATE_KEY" to "key",
            "GITHUB_APP_INSTALLATION_ID" to "not-a-number"
        )
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(env) }
        assertContains(ex.message!!, "GITHUB_APP_INSTALLATION_ID")
    }

    @Test
    fun `App auth fails fast when app ID is not numeric`() {
        val env = base - "GITHUB_PAT" + mapOf(
            "GITHUB_APP_ID" to "not-a-number",
            "GITHUB_APP_PRIVATE_KEY" to "key",
            "GITHUB_APP_INSTALLATION_ID" to "78901234"
        )
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(env) }
        assertContains(ex.message!!, "GITHUB_APP_ID")
    }

    @Test
    fun `App auth takes precedence over PAT when both present`() {
        val env = base + mapOf(
            "GITHUB_APP_ID" to "123456",
            "GITHUB_APP_PRIVATE_KEY" to "key",
            "GITHUB_APP_INSTALLATION_ID" to "78901234"
        )
        assertIs<AuthMode.App>(Config.fromEnv(env).authMode)
    }

    @Test
    fun `dummy push gateway address is accepted`() {
        val config = Config.fromEnv(base + ("PUSH_GATEWAY_ADDRESS" to "dummy"))
        assertEquals("dummy", config.pushGatewayAddress)
    }
}
