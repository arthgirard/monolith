package com.monolith.app.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Uploads every few hours while the member is in a group, so a phone left alone keeps its place
 * on the board. LeaderboardSyncer only uploads on a change, and a coroutine delay can't stand in:
 * its clock stops in deep sleep, where an idle phone spends most of its time, and Doze cuts the
 * network besides. WorkManager runs in Doze's maintenance windows and survives reboots, so even
 * a late run lands well inside the board's 48 h window.
 */
class LeaderboardHeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun leaderboardSyncer(): LeaderboardSyncer
    }

    override suspend fun doWork(): Result {
        val syncer = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).leaderboardSyncer()
        return try {
            if (syncer.syncNow()) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Leaderboard heartbeat failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LeaderboardHeartbeat"
        private const val WORK_NAME = "leaderboard_heartbeat"
        private const val INTERVAL_HOURS = 6L

        /** Runs the heartbeat while [joined], and stops it otherwise. Both are idempotent. */
        fun apply(context: Context, joined: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!joined) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<LeaderboardHeartbeatWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP: a process start must not push back a run that is already due.
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
