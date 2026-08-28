package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.StrictnessLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitWaiverUseCaseTest {

    private val appUnlockRepository = FakeAppUnlockRepository()
    private val packageName = "com.example.blocked"

    private fun submitAt(level: StrictnessLevel) =
        SubmitWaiverUseCase(appUnlockRepository, FakeStrictnessRepository(level))

    @Test
    fun `standard grants the unlock the waiver was typed for`() = runBlocking {
        assertTrue(submitAt(StrictnessLevel.STANDARD)(packageName, 5 * 60 * 1000L).isSuccess)

        assertNotNull(appUnlockRepository.observeUnlockedPackages().first()[packageName])
    }

    @Test
    fun `strict grants nothing even with the waiver typed out`() = runBlocking {
        assertTrue(submitAt(StrictnessLevel.STRICT)(packageName, 5 * 60 * 1000L).isFailure)

        assertNull(appUnlockRepository.observeUnlockedPackages().first()[packageName])
    }

    @Test
    fun `absolute grants nothing even with the waiver typed out`() = runBlocking {
        assertTrue(submitAt(StrictnessLevel.ABSOLUTE)(packageName, 5 * 60 * 1000L).isFailure)

        assertNull(appUnlockRepository.observeUnlockedPackages().first()[packageName])
    }
}
