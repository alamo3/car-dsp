package dev.alamo3.cabineq

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * "The car is the amplifier": when Android Auto (or any car mode) is active, hand the output
 * full scale so the mixer's stream-volume attenuation is out of the way before the audio
 * leaves the phone. The car's own volume knob then does the level, which is the point.
 *
 * Deliberately careful, because this hangs off the user's own volume:
 *   * only in car mode, and only with [Settings.maxVolumeInCar];
 *   * never when the platform owns the volume ([AudioManager.isVolumeFixed] — that is the
 *     Android Automotive case, where setting it does nothing but fight the car);
 *   * never over Bluetooth: there the phone's stream volume *is* the car's volume
 *     (absolute volume), so "max" would mean "turn the car to maximum";
 *   * never into the phone's own speaker unless asked, which is the one case where this
 *     is a way to get your ears blown off;
 *   * set once per car session, so turning it down afterwards sticks;
 *   * put back afterwards, but only if it is still at the maximum we set.
 */
object CarVolume {

    private const val TAG = "CabinEQ/CarVolume"

    fun inCar(context: Context): Boolean {
        val mode = runCatching {
            context.getSystemService(UiModeManager::class.java)?.currentModeType
        }.getOrNull() ?: return false
        return mode == Configuration.UI_MODE_TYPE_CAR
    }

    /** Called on car-mode transitions, route changes and playback start. Idempotent. */
    fun sync(context: Context) {
        val settings = Settings(context)
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (!inCar(context) || !settings.maxVolumeInCar) {
            restore(context, am, settings)
            return
        }
        apply(context, am, settings)
    }

    /** Manual override from the Output panel: does the same thing, ignores the car-mode test. */
    fun setMaxNow(context: Context) {
        val settings = Settings(context)
        val am = context.getSystemService(AudioManager::class.java) ?: return
        apply(context, am, settings)
    }

    fun restore(context: Context) {
        val settings = Settings(context)
        val am = context.getSystemService(AudioManager::class.java) ?: return
        restore(context, am, settings)
    }

    private fun apply(context: Context, am: AudioManager, settings: Settings) {
        if (runCatching { am.isVolumeFixed }.getOrDefault(false)) {
            Log.i(TAG, "volume is fixed by the device (car head unit) — leaving it alone")
            return
        }
        val route = FilePlayer.peek()?.routedDevice()?.type
        if (route != null && (route == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                route == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)) {
            Log.i(TAG, "Bluetooth: the car owns the volume, not us — leaving it alone")
            return
        }
        val ourSpeaker = route == null ||
            route == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            route == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        if (ourSpeaker && !settings.maxVolumeIncludeSpeaker) {
            Log.i(TAG, "routed to the phone's own speaker — not forcing it to maximum")
            return
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now >= max) return
        if (settings.preCarVolume < 0) settings.preCarVolume = now
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
        Log.i(TAG, "car mode: stream volume $now -> $max (restores to ${settings.preCarVolume} later)")
    }

    private fun restore(context: Context, am: AudioManager, settings: Settings) {
        val saved = settings.preCarVolume
        if (saved < 0) return
        settings.preCarVolume = -1
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Only undo what we did: if the listener moved it since, that is the level they want.
        if (now != max) {
            Log.i(TAG, "left the volume at $now/$max (moved since CabinEQ set it)")
            return
        }
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved.coerceIn(0, max), 0) }
        Log.i(TAG, "car mode ended: volume $max -> ${saved.coerceIn(0, max)}")
    }

    /** Short description for the Output panel. */
    fun describe(context: Context): String {
        val settings = Settings(context)
        val am = context.getSystemService(AudioManager::class.java)
        val car = inCar(context)
        val fixed = runCatching { am?.isVolumeFixed == true }.getOrDefault(false)
        return when {
            !settings.maxVolumeInCar -> if (car) "in car — room left to you" else "off"
            fixed -> if (car) "in car — fixed by the head unit" else "fixed by the device"
            settings.preCarVolume >= 0 -> "in car — set to maximum by CabinEQ"
            car -> "in car — not applied (Bluetooth or phone speaker)"
            else -> "on — applies in car mode"
        }
    }
}
