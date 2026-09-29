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
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

interface LeaderboardApi {
    suspend fun createGroup(request: CreateGroupRequest): LeaderboardResult<JoinResponse>
    suspend fun join(request: JoinRequest): LeaderboardResult<JoinResponse>
    suspend fun me(token: String): LeaderboardResult<MeResponse>
    suspend fun updateMe(token: String, request: UpdateMeRequest): LeaderboardResult<MeResponse>
    suspend fun sync(token: String, request: SyncRequest): LeaderboardResult<Unit>
    suspend fun board(token: String, window: BoardWindow, date: LocalDate): LeaderboardResult<BoardResponse>
    suspend fun leave(token: String): LeaderboardResult<Unit>
}

/** Plain HttpURLConnection, like UpdateRepositoryImpl: seven small JSON calls need no HTTP library. */
@Singleton
class HttpLeaderboardApi @Inject constructor() : LeaderboardApi {

    private val baseUrl = BuildConfig.LEADERBOARD_URL.trimEnd('/')

    override suspend fun createGroup(request: CreateGroupRequest) =
        call("POST", "/groups", null, LeaderboardJson.encodeToString(request)) { LeaderboardJson.decodeFromString<JoinResponse>(it) }

    override suspend fun join(request: JoinRequest) =
        call("POST", "/join", null, LeaderboardJson.encodeToString(request)) { LeaderboardJson.decodeFromString<JoinResponse>(it) }

    override suspend fun me(token: String) =
        call("GET", "/me", token, null) { LeaderboardJson.decodeFromString<MeResponse>(it) }

    override suspend fun updateMe(token: String, request: UpdateMeRequest) =
        call("POST", "/me", token, LeaderboardJson.encodeToString(request)) { LeaderboardJson.decodeFromString<MeResponse>(it) }

    override suspend fun sync(token: String, request: SyncRequest) =
        call("POST", "/sync", token, LeaderboardJson.encodeToString(request)) { }

    override suspend fun board(token: String, window: BoardWindow, date: LocalDate) =
        call("GET", "/board?window=${window.apiName}&date=$date", token, null) { LeaderboardJson.decodeFromString<BoardResponse>(it) }

    override suspend fun leave(token: String) = call("DELETE", "/me", token, null) { }

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
