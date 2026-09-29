package com.monolith.app.di

import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.usecase.AfterRestore
import com.monolith.app.domain.usecase.SnapshotWriter
import com.monolith.app.service.ScheduleTrigger
import com.monolith.app.widget.TimeSavedWidgetRefresher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    fun provideSnapshotWriter(preferences: MonolithPreferences): SnapshotWriter = object : SnapshotWriter {
        override suspend fun write(snapshot: BackupSnapshot) = preferences.restoreSnapshot(snapshot)
    }

    @Provides
    fun provideAfterRestore(widget: TimeSavedWidgetRefresher, scheduleTrigger: ScheduleTrigger): AfterRestore =
        object : AfterRestore {
            override suspend fun run() {
                widget.refresh()
                // Restored schedules need their alarm armed; the old one may point at a deleted one.
                scheduleTrigger.reconcile()
            }
        }
}
