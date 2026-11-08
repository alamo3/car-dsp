package dev.alamo3.cabineq

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * Silences an app's own output while CabinEQ plays the captured + processed copy.
 *
 * Playback capture taps the audio *before* session effects, so a −200 dB input gain on
 * the app's session mutes what it plays directly while the capture still receives the
 * full signal. (Same technique RootlessJamesDSP uses.)
 */
class SessionMuter {
    private val mutes = HashMap<Int, DynamicsProcessing>()

    val sessionIds: Set<Int> get() = mutes.keys

    fun mute(sessionId: Int): Boolean {
        if (mutes.containsKey(sessionId)) return true
        return try {
            val dp = DynamicsProcessing(Int.MAX_VALUE, sessionId, null)
            dp.setInputGainAllChannelsTo(-200f)
            dp.setEnabled(true)
            mutes[sessionId] = dp
            Log.i(TAG, "Muted session $sessionId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mute session $sessionId", e)
            false
        }
    }

    fun unmute(sessionId: Int) {
        mutes.remove(sessionId)?.let {
            runCatching {
                it.setEnabled(false)
                it.release()
            }
            Log.i(TAG, "Unmuted session $sessionId")
        }
    }

    fun unmuteAll() {
        for (id in mutes.keys.toList()) unmute(id)
    }

    companion object {
        private const val TAG = "CabinEQ/Muter"
    }
}
