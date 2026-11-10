package dev.alamo3.cabineq

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Measured loudness per song (LUFS), so a song heard once plays at the right level from its
 * first note afterwards. A small JSON map in `filesDir`; the newest few thousand are kept.
 */
object LoudnessStore {
    private const val TAG = "CabinEQ/Loudness"
    private const val FILE = "loudness.json"
    private const val MAX = 4000

    private val map = object : LinkedHashMap<String, Double>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > MAX
    }
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            for (k in o.keys()) o.optDouble(k).takeIf { !it.isNaN() }?.let { map[k] = it }
        }.onFailure { Log.w(TAG, "Couldn't read loudness data: ${it.message}") }
    }

    fun get(context: Context, track: Track): Double? = synchronized(map) {
        load(context)
        Taste.keys(track).firstNotNullOfOrNull { map[it] }
    }

    /** The gain songs have needed on average: the best first guess for one never heard. */
    fun typicalGainDb(context: Context): Double? = synchronized(map) {
        load(context)
        if (map.size < 5) null else
            map.values.map { (dev.alamo3.cabineq.music.Leveler.TARGET_LUFS - it)
                .coerceIn(dev.alamo3.cabineq.music.Leveler.MAX_CUT_DB, dev.alamo3.cabineq.music.Leveler.MAX_BOOST_DB) }.average()
    }

    fun put(context: Context, track: Track, lufs: Double) {
        if (lufs.isNaN() || lufs > 0 || lufs < -60) return
        val text = synchronized(map) {
            load(context)
            map[Taste.key(track)] = Math.round(lufs * 10) / 10.0
            JSONObject().apply { for ((k, v) in map) put(k, v) }.toString()
        }
        runCatching { File(context.applicationContext.filesDir, FILE).writeText(text) }
            .onFailure { Log.w(TAG, "Couldn't save loudness data: ${it.message}") }
    }
}
