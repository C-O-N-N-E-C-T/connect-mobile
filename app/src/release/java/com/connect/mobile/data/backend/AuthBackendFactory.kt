package com.connect.mobile.data.backend

import com.connect.mobile.config.AppConfig
import com.connect.mobile.util.AppLogger
import io.ktor.client.HttpClient

/** Release variant: always the real backend. The fake does not exist in this source set. */
object AuthBackendFactory {
    fun create(config: AppConfig, client: HttpClient, log: AppLogger): AuthBackend =
        KtorAuthBackend(client, config.apiBaseUrl, log)
}
