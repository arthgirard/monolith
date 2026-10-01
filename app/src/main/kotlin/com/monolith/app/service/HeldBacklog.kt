package com.monolith.app.service

/**
 * What the notification listener holds back while Monolith blocks, bounded however long the
 * block runs: a phone blocking dozens of apps for days would otherwise keep every notification
 * they posted, in the same process as enforcement. An update to a held notification replaces it
 * (a chat thread or a progress bar is one notification, however often it changes), and each app
 * keeps only its newest [perApp], counting the rest so the catch-up can still say how many.
 */
class HeldBacklog<T>(private val perApp: Int = PER_APP) {

    /** One app's share of a drain: its newest notifications first, and how many it posted in all. */
    class App<T>(val packageName: String, val newestFirst: List<T>, val count: Int, val lastAt: Long)

    private class Held<T>(val item: T, val at: Long)

    private class Entry<T> {
        val held = LinkedHashMap<String, Held<T>>()
        var dropped = 0
    }

    private val apps = HashMap<String, Entry<T>>()

    @Synchronized
    fun add(packageName: String, key: String, item: T, at: Long) {
        val entry = apps.getOrPut(packageName) { Entry() }
        // Removed first so an update moves to the newest end.
        entry.held.remove(key)
        entry.held[key] = Held(item, at)
        if (entry.held.size > perApp) {
            entry.held.remove(entry.held.keys.first())
            entry.dropped++
        }
    }

    /** Takes everything held for [onlyPackage], or for every app if null, most recent app first. */
    @Synchronized
    fun drain(onlyPackage: String? = null): List<App<T>> {
        val packages = if (onlyPackage == null) apps.keys.toList() else listOf(onlyPackage)
        return packages
            .mapNotNull { packageName ->
                val entry = apps.remove(packageName) ?: return@mapNotNull null
                val held = entry.held.values.reversed()
                App(packageName, held.map { it.item }, held.size + entry.dropped, held.first().at)
            }
            .sortedByDescending { it.lastAt }
    }

    companion object {
        const val PER_APP = 5
    }
}
