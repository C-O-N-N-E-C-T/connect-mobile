package com.connect.mobile.data.backend

import com.connect.mobile.config.AppConfig
import com.connect.mobile.util.AppLogger
import io.ktor.client.HttpClient

/** Debug variant: the fake OSP-60 backend unless CONNECT_FAKE_BACKEND=false in local.properties. */
object AuthBackendFactory {
    fun create(config: AppConfig, client: HttpClient, log: AppLogger): AuthBackend =
        if (config.useFakeBackend) {
            log.debug("Using debug FakeAuthBackend (CONNECT_FAKE_BACKEND)")
            FakeAuthBackend()
        } else {
            KtorAuthBackend(client, config.apiBaseUrl, log)
        }
}
