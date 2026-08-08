package com.monolith.app.domain.usecase

import kotlin.random.Random

/**
 * Builds the sentence a solver must copy exactly to confirm an unlock. Structurally different
 * templates (not just word substitutions of one another) so it stays something you have to
 * actually read and retype, not a shape you've memorized from last time.
 */
object WaiverGenerator {

    private val templates: List<(label: String, streak: String) -> String> = listOf(
        { label, streak ->
            "UNLOCKING $label FOR FIVE MINUTES ENDS YOUR STREAK OF $streak AND IT WILL NOT COME BACK. " +
                "YOU ARE CHOOSING $label OVER THE STREAK YOU BUILT, AND NO VERSION OF YOU TOMORROW WILL " +
                "BE ABLE TO UNDO THIS DECISION TODAY."
        },
        { label, streak ->
            "$streak DISAPPEARS THE MOMENT YOU UNLOCK $label FOR FIVE MINUTES. THAT TIME WAS SPENT NOT " +
                "LOOKING AT $label, AND IN FIVE MINUTES IT WILL BE GONE AS IF NONE OF IT EVER HAPPENED."
        },
        { label, streak ->
            "YOU HELD OUT FOR $streak BUT $label GETS FIVE MINUTES AND YOUR STREAK DIES RIGHT HERE. EVERY " +
                "MINUTE YOU DID NOT SPEND ON $label BUILT THIS STREAK, AND ONE UNLOCK ERASES ALL OF IT AT ONCE."
        },
        { label, streak ->
            "TRADE $streak OF PROGRESS FOR FIVE MINUTES INSIDE $label THAT CANNOT BE UNDONE. WHEN THE " +
                "FIVE MINUTES END YOU WILL STILL HAVE $label, BUT THE STREAK OF $streak WILL BE GONE FOR GOOD."
        },
        { label, streak ->
            "FIVE MINUTES WITH $label IS ALL IT TAKES TO ERASE A STREAK OF $streak. NOTHING ABOUT $label " +
                "IS WORTH MORE THAN THE TIME YOU ALREADY SPENT NOT OPENING IT, YET HERE YOU ARE ABOUT TO GIVE IT UP."
        },
        { label, streak ->
            "$label WINS FIVE MINUTES RIGHT NOW WHILE YOUR STREAK OF $streak QUIETLY ENDS. THIS IS NOT A " +
                "PAUSE, IT IS THE END OF THE STREAK, AND THE NEXT ONE STARTS FROM ZERO THE MOMENT YOU UNLOCK $label."
        },
        { label, streak ->
            "BREAKING A STREAK OF $streak BUYS EXACTLY FIVE MINUTES INSIDE $label. THAT IS THE FULL TRADE, " +
                "$streak OF DISCIPLINE FOR FIVE MINUTES ON $label, AND THERE IS NO WAY TO GET THE STREAK BACK AFTERWARD."
        },
        { label, streak ->
            "OPENING $label RIGHT NOW THROWS AWAY $streak OF DISCIPLINE FOR FIVE MINUTES. YOU BUILT THAT " +
                "STREAK ONE BLOCKED MOMENT AT A TIME, AND TYPING THIS SENTENCE IS THE LAST STEP BEFORE YOU " +
                "GIVE $streak OF IT UP FOR $label."
        },
    )

    fun generate(appLabel: String, currentStreakMillis: Long, random: Random = Random.Default): String {
        val label = appLabel.uppercase()
        val streak = streakPhrase(currentStreakMillis)
        return templates.random(random)(label, streak)
    }

    private fun streakPhrase(streakMillis: Long): String {
        val totalMinutes = (streakMillis / 60_000L).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours HOURS AND $minutes MINUTES"
            hours > 0 -> "$hours HOURS"
            else -> "$minutes MINUTES"
        }
    }
}
