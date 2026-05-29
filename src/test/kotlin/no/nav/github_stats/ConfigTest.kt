package no.nav.github_stats

import kotlin.test.*

class ConfigTest {
    private val base =
        mapOf(
            "GITHUB_ORG" to "my-org",
            "PUSH_GATEWAY_ADDRESS" to "http://pushgateway:9091",
            "GITHUB_APP_ID" to "123456",
            "GITHUB_APP_PRIVATE_KEY" to "base64key==",
            "GITHUB_APP_INSTALLATION_ID" to "78901234",
        )

    @Test
    fun `parses valid config`() {
        val config = Config.fromEnv(base)
        assertEquals("my-org", config.githubOrg)
        assertEquals("http://pushgateway:9091", config.pushGatewayAddress)
        assertEquals(123456L, config.appId)
        assertEquals("base64key==", config.privateKey)
        assertEquals(78901234L, config.installationId)
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
    fun `fails fast on missing GITHUB_ORG`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base - "GITHUB_ORG") }
        assertContains(ex.message!!, "GITHUB_ORG")
    }

    @Test
    fun `fails fast on missing PUSH_GATEWAY_ADDRESS`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base - "PUSH_GATEWAY_ADDRESS") }
        assertContains(ex.message!!, "PUSH_GATEWAY_ADDRESS")
    }

    @Test
    fun `fails fast with no auth configured`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base - "GITHUB_APP_ID") }
        assertContains(ex.message!!, "Authentication required")
    }

    @Test
    fun `accumulates multiple validation errors`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(emptyMap()) }
        assertContains(ex.message!!, "GITHUB_ORG")
        assertContains(ex.message!!, "PUSH_GATEWAY_ADDRESS")
        assertContains(ex.message!!, "Authentication required")
    }

    @Test
    fun `fails fast when private key is missing`() {
        val ex = assertFailsWith<IllegalArgumentException> { Config.fromEnv(base - "GITHUB_APP_PRIVATE_KEY") }
        assertContains(ex.message!!, "GITHUB_APP_PRIVATE_KEY")
    }

    @Test
    fun `fails fast when installation ID is not numeric`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                Config.fromEnv(base + ("GITHUB_APP_INSTALLATION_ID" to "not-a-number"))
            }
        assertContains(ex.message!!, "GITHUB_APP_INSTALLATION_ID")
    }

    @Test
    fun `fails fast when app ID is not numeric`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                Config.fromEnv(base + ("GITHUB_APP_ID" to "not-a-number"))
            }
        assertContains(ex.message!!, "GITHUB_APP_ID")
    }

    @Test
    fun `dummy push gateway address is accepted`() {
        val config = Config.fromEnv(base + ("PUSH_GATEWAY_ADDRESS" to "dummy"))
        assertEquals("dummy", config.pushGatewayAddress)
    }
}
