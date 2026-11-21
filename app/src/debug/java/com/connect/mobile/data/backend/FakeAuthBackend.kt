package com.connect.mobile.data.backend

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * DEBUG ONLY (src/debug; not in the release APK). Imitates the OSP-60 backend until it exists.
 * Reads (WITHOUT verifying) the `sub` and `user_role` claims of the real Supabase access token.
 */
class FakeAuthBackend(
    private val now: () -> Instant = Instant::now,
    private val delayMs: Long = 300,
) : AuthBackend {

    // OSP-60 ASSUMPTION: user_role == null (inactive/missing profile) -> 403 ACCOUNT_DISABLED; rescuer -> 72 h grant; other roles -> 200 without grant — see docs/osp-60-contract-assumptions.md
    override suspend fun bindSession(
        accessToken: String,
        deviceId: String,
        deviceName: String,
    ): BackendResult<SessionResponse> {
        delay(delayMs)
        val claims = decodeClaims(accessToken)
            ?: return BackendResult.Err(BackendError.Http(401, "UNAUTHENTICATED", "fake: unreadable token"))
        val userId = claims.string("sub")
            ?: return BackendResult.Err(BackendError.Http(401, "UNAUTHENTICATED", "fake: no sub"))
        val role = claims.string("user_role")
            ?: return BackendResult.Err(BackendError.Http(403, "ACCOUNT_DISABLED", "fake: user_role is null"))
        val grant = if (role == "rescuer") {
            val issued = now()
            OfflineMissionGrantDto(
                userId = userId,
                deviceId = deviceId,
                authenticatedAt = ISO.format(issued.atOffset(ZoneOffset.UTC)),
                expiresAt = ISO.format(issued.plus(GRANT_DURATION).atOffset(ZoneOffset.UTC)),
            )
        } else {
            null
        }
        return BackendResult.Ok(SessionResponse(userId = userId, role = role, deviceId = deviceId, grant = grant))
    }

    override suspend fun logout(accessToken: String): BackendResult<Unit> {
        delay(delayMs)
        return BackendResult.Ok(Unit)
    }

    private fun decodeClaims(jwt: String): JsonObject? = runCatching {
        val payload = jwt.split('.')[1]
        val text = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
        Json.parseToJsonElement(text) as JsonObject
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        val GRANT_DURATION: Duration = Duration.ofHours(72)
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
