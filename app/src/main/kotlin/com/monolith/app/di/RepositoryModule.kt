package com.monolith.app.di

import com.monolith.app.data.repository.AppRepositoryImpl
import com.monolith.app.data.repository.BlockRepositoryImpl
import com.monolith.app.data.repository.UpdateRepositoryImpl
import com.monolith.app.domain.repository.AppRepository
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.TagProvisioner
import com.monolith.app.domain.repository.UpdateRepository
import com.monolith.app.nfc.NfcManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindAppRepository(impl: AppRepositoryImpl): AppRepository

    @Binds
    abstract fun bindBlockRepository(impl: BlockRepositoryImpl): BlockRepository

    @Binds
    abstract fun bindTagProvisioner(impl: NfcManager): TagProvisioner

    @Binds
    abstract fun bindUpdateRepository(impl: UpdateRepositoryImpl): UpdateRepository
}
