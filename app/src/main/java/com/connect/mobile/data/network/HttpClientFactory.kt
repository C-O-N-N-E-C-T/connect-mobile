package com.connect.mobile.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The single HTTP stack of the app: Ktor on the OkHttp engine (no CIO), so the Android Network
 * Security Config (HTTPS only) applies to every request. No Logging plugin: tokens must never be logged.
 */
object HttpClientFactory {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** [requestTimeoutMillis] is a parameter only so tests need not wait 15 s. */
    fun create(
        engine: HttpClientEngine = OkHttp.create(),
        requestTimeoutMillis: Long = 15_000,
    ): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            this.requestTimeoutMillis = requestTimeoutMillis
        }
    }
}
