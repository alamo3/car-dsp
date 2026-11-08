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
}
