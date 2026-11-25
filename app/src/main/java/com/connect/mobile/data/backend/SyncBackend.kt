package com.connect.mobile.data.backend

import com.connect.mobile.data.network.HttpClientFactory
import com.connect.mobile.util.AppLogger
import com.connect.mobile.util.causeChainMessages
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * connect-backend POST /api/v1/sync (OSP-76 step 4, FR-R-12 -> FR-B-01). One queued record per
 * request; the backend answers 201 when the observation and its audit row are stored.
 */
interface SyncBackend {
    suspend fun upload(accessToken: String, payload: JsonObject): BackendResult<Unit>
}

/** Request body of POST /api/v1/sync: `{ "payload": { ... } }` (backend `syncBodySchema`). */
@Serializable
data class SyncRequest(val payload: JsonObject)

/**
 * Same HTTP stack as [KtorAuthBackend] (OkHttp engine, so the HTTPS-only Network Security Config
 * applies). Errors use the backend's unified contract `{ error_code, message }`.
 */
class KtorSyncBackend(
    private val client: HttpClient,
    baseUrl: String,
    private val log: AppLogger = AppLogger.NONE,
) : SyncBackend {
    private val base = baseUrl.trimEnd('/')

    override suspend fun upload(accessToken: String, payload: JsonObject): BackendResult<Unit> {
        val response: HttpResponse = try {
            client.post("$base$SYNC_PATH") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(SyncRequest(payload))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Timeouts, no route, the OS cleartext refusal: all transient for the sync gate.
            log.debug("sync network failure: ${e.causeChainMessages()}")
            return BackendResult.Err(BackendError.Network)
        }
        if (response.status.isSuccess()) return BackendResult.Ok(Unit)

        val error = try {
            HttpClientFactory.json.decodeFromString(ErrorBody.serializer(), response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // Server message is for debugging only; never the token, never the payload.
        log.debug("sync HTTP ${response.status.value} ${error?.errorCode}")
        return BackendResult.Err(BackendError.Http(response.status.value, error?.errorCode, error?.message))
    }

    companion object {
        const val SYNC_PATH = "/api/v1/sync"
    }
}
