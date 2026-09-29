package com.monolith.app.data.leaderboard

import com.monolith.app.BuildConfig
import com.monolith.app.domain.model.BoardWindow
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

interface LeaderboardApi {
    /** Creates the identity whose token the phone derived from its master. */
    suspend fun register(request: RegisterRequest): LeaderboardResult<Unit>

    /** Moves the identity behind [token] to the token in [request]; [token] is dead after. */
    suspend fun rotateToken(token: String, request: RotateTokenRequest): LeaderboardResult<Unit>
    suspend fun createGroup(token: String, request: CreateGroupRequest): LeaderboardResult<GroupResponse>
    suspend fun join(token: String, request: JoinRequest): LeaderboardResult<GroupResponse>
    suspend fun me(token: String): LeaderboardResult<MeResponse>
    suspend fun updateMe(token: String, request: UpdateMeRequest): LeaderboardResult<MeResponse>
    suspend fun updateGroup(token: String, groupId: String, request: UpdateGroupRequest): LeaderboardResult<GroupResponse>
    suspend fun leaveGroup(token: String, groupId: String): LeaderboardResult<Unit>
    suspend fun sync(token: String, request: SyncRequest): LeaderboardResult<Unit>
    suspend fun board(token: String, groupId: String, window: BoardWindow, date: LocalDate): LeaderboardResult<BoardResponse>

    /** [bytes] is the encrypted blob: the server never sees anything it could read. */
    suspend fun putBackup(token: String, bytes: ByteArray): LeaderboardResult<Unit>

    /** NO_BACKUP when the server holds none. */
    suspend fun getBackup(token: String): LeaderboardResult<ByteArray>
    suspend fun deleteBackup(token: String): LeaderboardResult<Unit>
}

/** Plain HttpURLConnection, like UpdateRepositoryImpl: a dozen small calls need no HTTP library. */
@Singleton
class HttpLeaderboardApi @Inject constructor() : LeaderboardApi {

    private val baseUrl = BuildConfig.LEADERBOARD_URL.trimEnd('/')

    override suspend fun register(request: RegisterRequest) =
        call("POST", "/users", null, LeaderboardJson.encodeToString(request)) { }

    override suspend fun rotateToken(token: String, request: RotateTokenRequest) =
        call("POST", "/me/token", token, LeaderboardJson.encodeToString(request)) { }

    override suspend fun createGroup(token: String, request: CreateGroupRequest) =
        call("POST", "/groups", token, LeaderboardJson.encodeToString(request)) { LeaderboardJson.decodeFromString<GroupResponse>(it) }

    override suspend fun join(token: String, request: JoinRequest) =
        call("POST", "/join", token, LeaderboardJson.encodeToString(request)) { LeaderboardJson.decodeFromString<GroupResponse>(it) }

    override suspend fun me(token: String) =
        call("GET", "/me", token, null) { LeaderboardJson.decodeFromString<MeResponse>(it) }

    override suspend fun updateMe(token: String, request: UpdateMeRequest) =
        call("POST", "/me", token, LeaderboardJson.encodeToString(request)) { LeaderboardJson.decodeFromString<MeResponse>(it) }

    override suspend fun updateGroup(token: String, groupId: String, request: UpdateGroupRequest) =
        call("POST", "/groups/${encode(groupId)}", token, LeaderboardJson.encodeToString(request)) {
            LeaderboardJson.decodeFromString<GroupResponse>(it)
        }

    override suspend fun leaveGroup(token: String, groupId: String) =
        call("DELETE", "/groups/${encode(groupId)}", token, null) { }

    override suspend fun sync(token: String, request: SyncRequest) =
        call("POST", "/sync", token, LeaderboardJson.encodeToString(request)) { }

    override suspend fun board(token: String, groupId: String, window: BoardWindow, date: LocalDate) =
        call("GET", "/groups/${encode(groupId)}/board?window=${window.apiName}&date=$date", token, null) {
            LeaderboardJson.decodeFromString<BoardResponse>(it)
        }

    override suspend fun putBackup(token: String, bytes: ByteArray) =
        callBytes("PUT", "/backup", token, bytes) { }

    override suspend fun getBackup(token: String) =
        callBytes("GET", "/backup", token, null) { it }

    override suspend fun deleteBackup(token: String) =
        callBytes("DELETE", "/backup", token, null) { }

    private fun encode(groupId: String): String = URLEncoder.encode(groupId, "UTF-8")

    private suspend fun <T> call(
        method: String,
        path: String,
        token: String?,
        body: String?,
        parse: (String) -> T,
    ): LeaderboardResult<T> =
        exchange(method, path, token, body?.toByteArray(), JSON) { parse(it.toString(Charsets.UTF_8)) }

    private suspend fun <T> callBytes(
        method: String,
        path: String,
        token: String,
        body: ByteArray?,
        parse: (ByteArray) -> T,
    ): LeaderboardResult<T> = exchange(method, path, token, body, OCTET_STREAM, parse)

    /** Errors always come back as JSON, whatever [contentType] the call itself speaks. */
    private suspend fun <T> exchange(
        method: String,
        path: String,
        token: String?,
        body: ByteArray?,
        contentType: String,
        parse: (ByteArray) -> T,
    ): LeaderboardResult<T> = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Accept", if (contentType == JSON) JSON else "$contentType, $JSON")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                    setRequestProperty("Content-Type", contentType)
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                if (status in 200..299) {
                    val bytes = if (status == HttpURLConnection.HTTP_NO_CONTENT) ByteArray(0) else connection.inputStream.use { it.readBytes() }
                    LeaderboardResult.Ok(parse(bytes))
                } else {
                    val text = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    val code = text?.let { runCatching { LeaderboardJson.decodeFromString<ErrorResponse>(it).error }.getOrNull() }
                    LeaderboardResult.Err(errorOf(code))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            LeaderboardResult.Err(LeaderboardError.NETWORK)
        } catch (e: SerializationException) {
            LeaderboardResult.Err(LeaderboardError.SERVER)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val JSON = "application/json"
        const val OCTET_STREAM = "application/octet-stream"
    }
}
