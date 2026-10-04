package com.monolith.app.ui.friends

import com.monolith.app.domain.model.BoardRow

/** Every stat on [row] is hidden from this viewer: the row says so once instead of a run of dashes. */
fun sharesNoStats(row: BoardRow): Boolean =
    row.savedMillis == null && !row.streakVisible && row.bypassCount == null && row.unlockCount == null
