package dev.soloton.api

/**
 * Somewhere to keep small settings between runs: text values under text keys. The launcher
 * supplies one that lasts (the tablet's preferences); the default forgets when the app closes.
 * Called from the render thread; writes must not block.
 */
interface SettingsStore {
    /** The value saved under [key], or null if there is none. */
    fun get(key: String): String?

    fun put(key: String, value: String)
}

/** Settings kept in memory only: for the desktop launcher and for tests. */
class MemorySettingsStore : SettingsStore {
    private val values = HashMap<String, String>()

    override fun get(key: String): String? = synchronized(values) { values[key] }

    override fun put(key: String, value: String) {
        synchronized(values) { values[key] = value }
    }
}
