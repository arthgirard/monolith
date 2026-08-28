package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.StrictnessLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartBypassUseCaseTest {

    private val blockRepository = FakeBlockRepository(initiallyActive = true)
    private val appUnlockRepository = FakeAppUnlockRepository()
    private val strictnessRepository = FakeStrictnessRepository()
    private val startBypass = StartBypassUseCase(blockRepository, appUnlockRepository, strictnessRepository)

    private val packageName = "com.example.blocked"

    /** A code-breaker whose last guess was the secret: solved, waiting only on its waiver. */
    private fun solvedCodeBreaker(): CodeBreaker {
        val secret = listOf(0, 1, 2, 3, 4, 5)
        return CodeBreaker(secret = secret).withGuess(secret).also { assertTrue(it.isSolved) }
    }

    @Test
    fun `the bypass window starts`() = runBlocking {
        startBypass()

        assertTrue(blockRepository.observeBlockState().first().isBypassActive(System.currentTimeMillis()))
    }

    @Test
    fun `a solved puzzle waiting on its waiver does not survive the bypass`() = runBlocking {
        appUnlockRepository.saveCodeBreaker(packageName, solvedCodeBreaker())

        startBypass()

        assertNull(
            "the overlay must come back on the idle block screen, not that app's leftover waiver",
            appUnlockRepository.observeCodeBreakers().first()[packageName],
        )
        assertEquals(1, appUnlockRepository.clearUnlocksCount)
    }

    @Test
    fun `an app unlock bought before the bypass does not survive it`() = runBlocking {
        appUnlockRepository.grantUnlock(packageName, 5 * 60 * 1000L)

        startBypass()

        assertNull(appUnlockRepository.observeUnlockedPackages().first()[packageName])
    }

    @Test
    fun `strict still allows the one bypass per lock`() = runBlocking {
        strictnessRepository.setStrictness(StrictnessLevel.STRICT)

        assertTrue(startBypass().isSuccess)
        assertTrue(blockRepository.observeBlockState().first().isBypassActive(System.currentTimeMillis()))
    }

    @Test
    fun `absolute opens no bypass window at all`() = runBlocking {
        strictnessRepository.setStrictness(StrictnessLevel.ABSOLUTE)

        assertTrue(startBypass().isFailure)
        assertFalse(blockRepository.observeBlockState().first().isBypassActive(System.currentTimeMillis()))
    }

    @Test
    fun `a refused bypass leaves a solved puzzle where it was`() = runBlocking {
        strictnessRepository.setStrictness(StrictnessLevel.ABSOLUTE)
        appUnlockRepository.saveCodeBreaker(packageName, solvedCodeBreaker())

        startBypass()

        assertEquals(0, appUnlockRepository.clearUnlocksCount)
    }
}
