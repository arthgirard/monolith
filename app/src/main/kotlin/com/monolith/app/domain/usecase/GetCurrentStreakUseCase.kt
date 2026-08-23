package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockSession
import com.monolith.app.domain.repository.BlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * How long the current streak has run: the time credited to blocking since it was last turned on,
 * with bypass and app-unlock windows already carved out by [TimeSavedCalculator].
 *
 * The block overlay shows this number and the waiver sentence names it. They have to agree --
 * a wall that says "held 4h 12m" above a sentence that says "your streak of 3 HOURS" reads as a
 * bug -- so both go through here rather than each summing sessions themselves.
 */
class GetCurrentStreakUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
) {
    /** A one-shot read, for callers building a string once (the waiver sentence). */
    suspend fun current(now: Long = System.currentTimeMillis()): Long {
        val blockState = blockRepository.observeBlockState().first()
        val activeSessionStart = blockRepository.observeActiveSessionStart().first()
        return streakOf(blockState, activeSessionStart, now)
    }

    /**
     * A live read, for the overlay's ticking readout. Recomputed on every [ticker] emission
     * rather than on a timer of its own, so the caller owns the cadence.
     */
    operator fun invoke(ticker: Flow<Long>): Flow<Long> = combine(
        blockRepository.observeBlockState(),
        blockRepository.observeActiveSessionStart(),
        ticker,
    ) { blockState, activeSessionStart, now ->
        streakOf(blockState, activeSessionStart, now)
    }

    private fun streakOf(
        blockState: com.monolith.app.domain.model.BlockState,
        activeSessionStart: Long?,
        now: Long,
    ): Long = streakOf(TimeSavedCalculator.ongoingSessions(blockState, activeSessionStart, now))

    companion object {
        /**
         * The streak carried by sessions already in hand, for a caller that has computed
         * [TimeSavedCalculator.ongoingSessions] for its own reasons -- the widget does, to draw
         * today's bars -- and would otherwise re-read the same two values to get this number.
         */
        fun streakOf(ongoing: List<BlockSession>): Long = ongoing.sumOf { it.durationMillis }
    }
}
