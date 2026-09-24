package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.CodeBreaker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeBlockingUseCaseTest {

    private val appUnlockRepository = FakeAppUnlockRepository()
    private val blockRepository = FakeBlockRepository(initiallyActive = true, appUnlocks = appUnlockRepository)
    private val resumeBlocking = ResumeBlockingUseCase(blockRepository, appUnlockRepository)
    private val startBypass = StartBypassUseCase(blockRepository, appUnlockRepository, FakeStrictnessRepository())

    private val packageName = "com.example.blocked"

    @Test
    fun `a running bypass ends now`() = runBlocking {
        startBypass()

        assertTrue(resumeBlocking())

        val state = blockRepository.observeBlockState().first()
        assertFalse(state.isBypassActive(System.currentTimeMillis()))
        assertTrue("an ended bypass is still the one bypass of this cycle", state.bypassUsed)
    }

    @Test
    fun `live app unlocks end now`() = runBlocking {
        appUnlockRepository.grantUnlock(packageName, 5 * 60 * 1000L)

        assertTrue(resumeBlocking())

        val now = System.currentTimeMillis()
        assertTrue(appUnlockRepository.observeUnlockedPackages().first().values.none { it > now })
    }

    @Test
    fun `a solved puzzle waiting on its waiver is left where it was`() = runBlocking {
        val secret = listOf(0, 1, 2, 3, 4, 5)
        appUnlockRepository.saveCodeBreaker(packageName, CodeBreaker(secret = secret).withGuess(secret))
        appUnlockRepository.grantUnlock("com.example.other", 5 * 60 * 1000L)

        resumeBlocking()

        assertNotNull(appUnlockRepository.observeCodeBreakers().first()[packageName])
    }

    @Test
    fun `nothing paused writes nothing`() = runBlocking {
        assertFalse(resumeBlocking())
        assertEquals(0, blockRepository.endPausesCount)
    }

    @Test
    fun `monolith off writes nothing`() = runBlocking {
        appUnlockRepository.grantUnlock(packageName, 5 * 60 * 1000L)
        blockRepository.setBlockModeActive(false)

        assertFalse(resumeBlocking())
        assertEquals(0, blockRepository.endPausesCount)
    }
}
