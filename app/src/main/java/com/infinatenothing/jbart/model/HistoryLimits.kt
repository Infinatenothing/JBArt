package com.infinatenothing.jbart.model

object HistoryLimits {
    /** A burst of edits stays undoable. Older steps drop so a long session cannot grow without bound. */
    const val MAX_ENTRIES = 100

    /** A few huge strokes cannot pin the undo stack in memory. The drawing itself is kept. */
    const val MAX_POINTS = 200_000
}
