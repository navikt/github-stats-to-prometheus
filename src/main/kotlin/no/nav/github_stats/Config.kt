package no.nav.github_stats

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Config")

data class Config(
    val githubOrg: String,
    val githubApiUrl: String,
    val githubApiVersion: String,
    val githubGraphqlUrl: String,
    val pushGatewayAddress: String,
    val appId: Long,
    val privateKey: String,
    val installationId: Long,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val errors = mutableListOf<String>()

            fun require(key: String) =
                env[key]?.takeIf { it.isNotBlank() }
                    ?: run {
                        errors.add("Missing required env var: $key")
                        ""
                    }

            fun optional(
                key: String,
                default: String = "",
            ) = env[key]?.takeIf { it.isNotBlank() } ?: default

            val org = require("GITHUB_ORG")
            val pushGateway = require("PUSH_GATEWAY_ADDRESS")
            val apiUrl = optional("GITHUB_API_URL", "https://api.github.com/").trimEnd('/') + "/"
            val apiVersion = optional("GITHUB_API_VERSION", "2026-03-10")
            val graphqlUrl = optional("GITHUB_GRAPHQL_URL", "https://api.github.com/graphql")

            val appIdRaw = env["GITHUB_APP_ID"]?.trim()
            val appId: Long?
            val privateKey: String?
            val installationId: Long?

            if (appIdRaw.isNullOrEmpty()) {
                errors.add("Authentication required: set GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY + GITHUB_APP_INSTALLATION_ID")
                appId = null
                privateKey = null
                installationId = null
            } else {
                appId = appIdRaw.toLongOrNull() ?: run {
                    errors.add("GITHUB_APP_ID must be numeric")
                    null
                }
                privateKey = env["GITHUB_APP_PRIVATE_KEY"]?.trim() ?: run {
                    errors.add("GITHUB_APP_PRIVATE_KEY is required when GITHUB_APP_ID is set")
                    null
                }
                installationId = env["GITHUB_APP_INSTALLATION_ID"]?.trim()?.toLongOrNull() ?: run {
                    errors.add("GITHUB_APP_INSTALLATION_ID must be numeric")
                    null
                }
            }

            if (errors.isNotEmpty()) {
                throw IllegalArgumentException(errors.joinToString("\n  - ", "Configuration errors:\n  - "))
            }

            return Config(
                org,
                apiUrl,
                apiVersion,
                graphqlUrl,
                pushGateway,
                appId!!,
                privateKey!!,
                installationId!!,
            ).also {
                log.info(
                    "org=${it.githubOrg} auth=App(${it.appId}) " +
                        "pushGateway=${if (pushGateway == "dummy") "dummy" else pushGateway}",
                )
            }
        }
    }
}
