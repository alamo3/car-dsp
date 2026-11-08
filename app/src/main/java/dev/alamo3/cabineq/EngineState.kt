package dev.alamo3.cabineq

/**
 * Snapshot of what the service is doing, read by the UI for its status panel.
 * Written only on the main thread by EqService; read on the main thread by MainActivity.
 */
object EngineState {
    @Volatile var running = false
    @Volatile var sessions: Map<Int, String> = emptyMap()   // sessionId -> package
    @Volatile var sessionsWithoutControl: List<Int> = emptyList()
    @Volatile var gpsActive = false
    @Volatile var rawKmh = 0f
    @Volatile var smoothedKmh = 0f
    @Volatile var speedAmount = 0f                           // 0..1
    @Volatile var appliedBands = FloatArray(Bands.COUNT)
    @Volatile var appliedInputGain = 0f

    // Native DSP path
    @Volatile var dspActive = false
    @Volatile var dspMessage: String? = null          // why DSP isn't running / fell back
    @Volatile var dspSampleRate = 0
    @Volatile var dspOutputPaused = true
    @Volatile var dspInPeakDb = -120f
    @Volatile var dspOutPeakDb = -120f
    @Volatile var dspLimiterDb = 0f                   // gain reduction, <= 0
    @Volatile var dspRoute: String? = null
    @Volatile var sessionModes: Map<Int, String> = emptyMap()  // sessionId -> "DSP" / "Android EQ"

    // In-app file player (TorBox). Track/state info lives on FilePlayer itself.
    @Volatile var playerOutPeakDb = -120f
    @Volatile var playerLimiterDb = 0f
    @Volatile var playerRoute: String? = null
    @Volatile var playerMessage: String? = null
}
