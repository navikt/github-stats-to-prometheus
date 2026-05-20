package no.nav.github_stats

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

private val log = LoggerFactory.getLogger("GitHubAuth")

fun resolveToken(auth: AuthMode, httpClient: HttpClient, apiUrl: String, apiVersion: String): String = when (auth) {
    is AuthMode.Pat -> auth.token.also { log.info("Using PAT authentication") }
    is AuthMode.App -> runBlocking {
        log.info("Using GitHub App authentication (appId=${auth.appId}, installationId=${auth.installationId})")
        val jwt = buildJwt(auth.appId, auth.privateKeyBase64)
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

private fun buildJwt(appId: Long, privateKeyBase64: String): String {
    val now = System.currentTimeMillis()
    val claims = JWTClaimsSet.Builder()
        .issuer(appId.toString())
        .issueTime(Date(now - 60_000L))
        .expirationTime(Date(now + 9 * 60_000L))
        .build()
    return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims)
        .also { it.sign(RSASSASigner(decodePrivateKey(privateKeyBase64))) }
        .serialize()
}

private fun decodePrivateKey(privateKeyBase64: String): RSAPrivateKey = try {
    val cleaned = privateKeyBase64
        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
        .replace("-----END RSA PRIVATE KEY-----", "")
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\s".toRegex(), "")
    KeyFactory.getInstance("RSA")
        .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(cleaned))) as RSAPrivateKey
} catch (e: Exception) {
    throw IllegalArgumentException("Failed to decode GITHUB_APP_PRIVATE_KEY: ${e.message}")
}

@Serializable
private data class InstallationTokenResponse(val token: String, val expires_at: String)
