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
    /** Without a [token] the server creates the identity and answers with its token. */
    suspend fun createGroup(token: String?, request: CreateGroupRequest): LeaderboardResult<GroupResponse>
    suspend fun join(token: String?, request: JoinRequest): LeaderboardResult<GroupResponse>
    suspend fun me(token: String): LeaderboardResult<MeResponse>
    suspend fun updateMe(token: String, request: UpdateMeRequest): LeaderboardResult<MeResponse>
    suspend fun updateGroup(token: String, groupId: String, request: UpdateGroupRequest): LeaderboardResult<GroupResponse>
    suspend fun leaveGroup(token: String, groupId: String): LeaderboardResult<Unit>
    suspend fun sync(token: String, request: SyncRequest): LeaderboardResult<Unit>
    suspend fun board(token: String, groupId: String, window: BoardWindow, date: LocalDate): LeaderboardResult<BoardResponse>
}

/** Plain HttpURLConnection, like UpdateRepositoryImpl: eight small JSON calls need no HTTP library. */
@Singleton
class HttpLeaderboardApi @Inject constructor() : LeaderboardApi {

    private val baseUrl = BuildConfig.LEADERBOARD_URL.trimEnd('/')

    override suspend fun createGroup(token: String?, request: CreateGroupRequest) =
        call("POST", "/groups", token, LeaderboardJson.encodeToString(request)) { LeaderboardJson.decodeFromString<GroupResponse>(it) }

    override suspend fun join(token: String?, request: JoinRequest) =
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

    private fun encode(groupId: String): String = URLEncoder.encode(groupId, "UTF-8")

    private suspend fun <T> call(
        method: String,
        path: String,
        token: String?,
        body: String?,
        parse: (String) -> T,
    ): LeaderboardResult<T> = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
                val status = connection.responseCode
                if (status in 200..299) {
                    val text = if (status == HttpURLConnection.HTTP_NO_CONTENT) "" else connection.inputStream.bufferedReader().use { it.readText() }
                    LeaderboardResult.Ok(parse(text))
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
    }
}
