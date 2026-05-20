package no.nav.github_stats

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Config")

enum class Role {
    PULL, TRIAGE, PUSH, MAINTAIN, ADMIN;

    fun meetsMinimum(minimum: Role): Boolean = ordinal >= minimum.ordinal

    companion object {
        fun fromString(value: String): Role =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Invalid MINIMUM_ROLE '$value'. Must be one of: ${entries.joinToString { it.name.lowercase() }}"
                )
    }
}

fun roleFromPermissions(p: Permissions): Role = when {
    p.admin -> Role.ADMIN
    p.maintain -> Role.MAINTAIN
    p.push -> Role.PUSH
    p.triage -> Role.TRIAGE
    else -> Role.PULL
}

sealed class AuthMode {
    data class Pat(val token: String) : AuthMode()
    data class App(val appId: Long, val privateKeyBase64: String, val installationId: Long) : AuthMode()
}

data class Config(
    val githubOrg: String,
    val githubTeams: List<String>,
    val githubApiUrl: String,
    val githubApiVersion: String,
    val pushGatewayAddress: String,
    val minimumRole: Role,
    val excludedRepos: Set<String>,
    val authMode: AuthMode
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val errors = mutableListOf<String>()

            fun require(key: String) = env[key]?.takeIf { it.isNotBlank() }
                ?: run { errors.add("Missing required env var: $key"); "" }

            fun optional(key: String, default: String = "") = env[key]?.takeIf { it.isNotBlank() } ?: default

            val org = require("GITHUB_ORG")
            val teamsRaw = require("GITHUB_TEAMS")
            val pushGateway = require("PUSH_GATEWAY_ADDRESS")
            val apiUrl = optional("GITHUB_API_URL", "https://api.github.com/").trimEnd('/') + "/"
            val apiVersion = optional("GITHUB_API_VERSION", "2026-03-10")

            val minimumRole = try {
                Role.fromString(optional("MINIMUM_ROLE", "admin"))
            } catch (e: IllegalArgumentException) {
                errors.add(e.message!!); Role.PUSH
            }

            val excludedRepos =
                optional("EXCLUDED_REPOS").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

            val authMode: AuthMode? = when (val appId = env["GITHUB_APP_ID"]?.trim()) {
                null -> when (val pat = env["GITHUB_PAT"]?.trim()) {
                    null, "" -> {
                        errors.add("Authentication required: set GITHUB_PAT or GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY + GITHUB_APP_INSTALLATION_ID"); null
                    }

                    else -> AuthMode.Pat(pat)
                }

                else -> {
                    val privateKey = env["GITHUB_APP_PRIVATE_KEY"]?.trim()
                        ?: run { errors.add("GITHUB_APP_PRIVATE_KEY is required when GITHUB_APP_ID is set"); null }
                    val installationId = env["GITHUB_APP_INSTALLATION_ID"]?.trim()?.toLongOrNull()
                        ?: run { errors.add("GITHUB_APP_INSTALLATION_ID must be numeric"); null }
                    val id = appId.toLongOrNull()
                        ?: run { errors.add("GITHUB_APP_ID must be numeric"); null }
                    if (privateKey != null && installationId != null && id != null)
                        AuthMode.App(id, privateKey, installationId)
                    else null
                }
            }

            if (errors.isNotEmpty()) throw IllegalArgumentException(
                errors.joinToString(
                    "\n  - ",
                    "Configuration errors:\n  - "
                )
            )

            val teams = teamsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (teams.isEmpty()) throw IllegalArgumentException("GITHUB_TEAMS must contain at least one team slug")

            return Config(org, teams, apiUrl, apiVersion, pushGateway, minimumRole, excludedRepos, authMode!!).also {
                log.info(
                    "org=${it.githubOrg} teams=${it.githubTeams} minimumRole=${it.minimumRole} " +
                            "auth=${if (authMode is AuthMode.App) "App(${authMode.appId})" else "PAT"} " +
                            "pushGateway=${if (pushGateway == "dummy") "dummy" else pushGateway}"
                )
            }
        }
    }
}
