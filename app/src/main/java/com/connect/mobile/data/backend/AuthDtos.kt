package com.connect.mobile.data.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The only file that knows backend wire names. OSP-60 is not implemented yet; every field below is
// a contract assumption documented in docs/osp-60-contract-assumptions.md.

// OSP-60 ASSUMPTION: request body of POST /api/v1/auth/session is {device_id, device_name} — see docs/osp-60-contract-assumptions.md
@Serializable
data class SessionRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
)

// OSP-60 ASSUMPTION: 200 response is {user_id, role, device_id, offline_mission_grant?}; grant absent for non-rescuers — see docs/osp-60-contract-assumptions.md
@Serializable
data class SessionResponse(
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName(GRANT_JSON_KEY) val grant: OfflineMissionGrantDto? = null,
)

// OSP-60 ASSUMPTION: grant fields user_id, device_id, authenticated_at, expires_at (ISO-8601 with offset) — see docs/osp-60-contract-assumptions.md
@Serializable
data class OfflineMissionGrantDto(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("authenticated_at") val authenticatedAt: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** Unified backend error contract (connect-backend src/types/errors.ts). */
@Serializable
data class ErrorBody(
    @SerialName("error_code") val errorCode: String,
    val message: String = "",
)

// OSP-60 ASSUMPTION: grant JSON key is "offline_mission_grant" — see docs/osp-60-contract-assumptions.md
const val GRANT_JSON_KEY = "offline_mission_grant"
