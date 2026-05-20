plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "no.nav"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "no.nav.github_stats.MainKt"
    applicationName = "app"
}

tasks {
    withType<Test> {
        useJUnitPlatform()
        testLogging {
            showExceptions = true
        }
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.prometheus.simpleclient)
    implementation(libs.prometheus.pushgateway)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    implementation(libs.auth0.java.jwt)
    implementation(libs.bouncy.castle.bcpkix)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test)
}
