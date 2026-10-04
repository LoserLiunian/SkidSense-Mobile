package com.skidsense.mobile.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/status`, the fields the login screen needs. */
@Serializable
data class ServerStatus(
    @SerialName("system_name") val systemName: String? = null,
    @SerialName("password_login_enabled") val passwordLoginEnabled: Boolean = true,
    @SerialName("geetest_check") val geetestCheck: Boolean = false,
    @SerialName("geetest_id") val geetestId: String? = null,
    @SerialName("turnstile_check") val turnstileCheck: Boolean = false,
    @SerialName("turnstile_site_key") val turnstileSiteKey: String? = null,
    @SerialName("server_address") val serverAddress: String? = null
)

@Serializable
data class LoginMethod(val method: String = "", val available: Boolean = true, val reason: String? = null)

/** The password was right and the account has a second factor. */
data class LoginChallenge(val flowToken: String, val methods: List<LoginMethod>)

@Serializable
data class UserInfo(val id: Long = 0, val username: String = "", @SerialName("display_name") val displayName: String? = null)

/** `GET /api/companion/config`. */
@Serializable
data class CompanionConfig(
    val enabled: Boolean = false,
    @SerialName("grant_public_key") val grantPublicKey: String? = null,
    @SerialName("access_ttl") val accessTtl: Long = 3600,
    @SerialName("enroll_ttl") val enrollTtl: Long = 600,
    @SerialName("ws_path") val wsPath: String? = null,
    val history: CompanionHistoryConfig? = null
)

@Serializable
data class CompanionHistoryConfig(
    val enabled: Boolean = false,
    @SerialName("max_blob_bytes") val maxBlobBytes: Long = 0,
    @SerialName("max_user_bytes") val maxUserBytes: Long = 0
)

/** One desktop registered under the account (spec §9). Times are Unix seconds. */
@Serializable
data class HostRow(
    @SerialName("host_id") val hostId: String,
    val name: String = "",
    @SerialName("public_key") val publicKey: String = "",
    val platform: String = "",
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("lan_addrs") val lanAddrs: List<String> = emptyList(),
    @SerialName("lan_port") val lanPort: Int = 0,
    val online: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("last_seen_at") val lastSeenAt: Long = 0
)

@Serializable
data class DeviceRow(
    @SerialName("device_id") val deviceId: String,
    @SerialName("host_id") val hostId: String = "",
    val name: String = "",
    @SerialName("public_key") val publicKey: String = "",
    val platform: String = "",
    val scopes: List<String> = emptyList(),
    /** `pending | active | revoked`. */
    val status: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("activated_at") val activatedAt: Long? = null,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null
)

@Serializable
data class DeviceRegistration(
    val device: DeviceRow,
    val ticket: String,
    @SerialName("ticket_expires_at") val ticketExpiresAt: Long = 0
)

@Serializable
data class GrantResponse(val grant: String, @SerialName("expires_at") val expiresAt: Long = 0)

@Serializable
data class HistoryKeyRow(val epoch: Long, val wrapped: String)

@Serializable
data class HistorySessionRow(
    @SerialName("session_key") val sessionKey: String,
    val epoch: Long,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val size: Long = 0
)

@Serializable
data class HistoryBlobRow(
    @SerialName("session_key") val sessionKey: String,
    val epoch: Long,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val blob: String
)
