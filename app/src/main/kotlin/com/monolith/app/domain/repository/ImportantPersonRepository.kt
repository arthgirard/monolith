package com.monolith.app.domain.repository

import com.monolith.app.domain.model.ImportantPerson
import kotlinx.coroutines.flow.Flow

interface ImportantPersonRepository {
    fun observeImportantPeople(): Flow<List<ImportantPerson>>

    suspend fun addImportantPerson(person: ImportantPerson)

    suspend fun removeImportantPerson(person: ImportantPerson)
}
