package no.nav.github_stats

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.slf4j.LoggerFactory
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date

private val log = LoggerFactory.getLogger("GitHubAuth")

fun resolveToken(auth: AuthMode, httpClient: HttpClient, apiUrl: String, apiVersion: String): String = when (auth) {
    is AuthMode.Pat -> auth.token.also { log.info("Using PAT authentication") }
    is AuthMode.App -> runBlocking {
        log.info("Using GitHub App authentication (appId=${auth.appId}, installationId=${auth.installationId})")
        val jwt = buildJwt(auth.appId, auth.privateKey)
        val response = httpClient.post("${apiUrl}app/installations/${auth.installationId}/access_tokens") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", apiVersion)
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) throw RuntimeException(
            "Failed to fetch installation token: HTTP ${response.status}. Check GITHUB_APP_ID, GITHUB_APP_INSTALLATION_ID, GITHUB_APP_PRIVATE_KEY."
        )
        response.body<InstallationTokenResponse>().token.also { log.info("Installation token obtained") }
    }
}

private fun buildJwt(appId: Long, privateKeyPem: String): String {
    val privateKey = loadPrivateKey(privateKeyPem)
    return JWT.create()
        .withIssuer(appId.toString())
        .withIssuedAt(Date.from(Instant.now().minusSeconds(60)))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(540)))
        .sign(Algorithm.RSA256(null, privateKey))
}

private fun loadPrivateKey(pemContent: String): RSAPrivateKey = try {
    val keyPair = PEMParser(StringReader(pemContent)).readObject() as PEMKeyPair
    JcaPEMKeyConverter().getKeyPair(keyPair).private as RSAPrivateKey
} catch (e: Exception) {
    throw IllegalArgumentException("Failed to load GITHUB_APP_PRIVATE_KEY: ${e.message}")
}

@Serializable
private data class InstallationTokenResponse(val token: String, val expires_at: String)

