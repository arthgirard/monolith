package com.monolith.app.data.repository

import com.monolith.app.data.datastore.MonolithPreferences
import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.repository.ImportantPersonRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportantPersonRepositoryImpl @Inject constructor(
    private val preferences: MonolithPreferences,
) : ImportantPersonRepository {

    override fun observeImportantPeople(): Flow<List<ImportantPerson>> = preferences.importantPeople

    override suspend fun addImportantPerson(person: ImportantPerson) {
        preferences.addImportantPerson(person)
    }

    override suspend fun removeImportantPerson(person: ImportantPerson) {
        preferences.removeImportantPerson(person)
    }
}
