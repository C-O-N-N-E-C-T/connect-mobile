package com.connect.mobile.data.backend

import com.connect.mobile.data.network.HttpClientFactory
import com.connect.mobile.util.AppLogger
import com.connect.mobile.util.causeChainMessages
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

class KtorAuthBackend(
    private val client: HttpClient,
    baseUrl: String,
    private val log: AppLogger = AppLogger.NONE,
) : AuthBackend {
    private val base = baseUrl.trimEnd('/')

    // OSP-60 ASSUMPTION: POST {API_BASE_URL}/api/v1/auth/session with Authorization: Bearer <supabase access_token> and a JSON body — see docs/osp-60-contract-assumptions.md
    override suspend fun bindSession(
        accessToken: String,
        deviceId: String,
        deviceName: String,
    ): BackendResult<SessionResponse> = execute(
        request = {
            client.post("$base$SESSION_PATH") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(SessionRequest(deviceId, deviceName))
            }
        },
        onSuccess = { it.body<SessionResponse>() },
    )

    // OSP-60 ASSUMPTION: POST /api/v1/auth/logout takes only the Bearer header (no body), any 2xx = done; an expired token may be sent — see docs/osp-60-contract-assumptions.md
    override suspend fun logout(accessToken: String): BackendResult<Unit> = execute(
        request = { client.post("$base$LOGOUT_PATH") { bearerAuth(accessToken) } },
        onSuccess = { },
    )

    private suspend fun <T> execute(
        request: suspend () -> HttpResponse,
        onSuccess: suspend (HttpResponse) -> T,
    ): BackendResult<T> {
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Includes the OS cleartext refusal (UnknownServiceException "CLEARTEXT communication … not permitted").
            log.debug("auth backend network failure: ${e.causeChainMessages()}")
            return BackendResult.Err(BackendError.Network)
        }
        if (response.status.isSuccess()) {
            return try {
                BackendResult.Ok(onSuccess(response))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("auth backend malformed ${response.status.value} body: ${e.javaClass.simpleName}")
                BackendResult.Err(BackendError.Malformed)
            }
        }
        // OSP-60 ASSUMPTION: non-2xx bodies use the unified error contract {error_code, message}; anything else is an error without a code — see docs/osp-60-contract-assumptions.md
        val error = try {
            HttpClientFactory.json.decodeFromString(ErrorBody.serializer(), response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // Server message is for debugging only; the UI shows localised text.
        log.debug("auth backend HTTP ${response.status.value} ${error?.errorCode}: ${error?.message}")
        return BackendResult.Err(BackendError.Http(response.status.value, error?.errorCode, error?.message))
    }

    companion object {
        const val SESSION_PATH = "/api/v1/auth/session"
        const val LOGOUT_PATH = "/api/v1/auth/logout"
    }
}
