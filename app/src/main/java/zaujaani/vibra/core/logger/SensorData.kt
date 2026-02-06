package zaujaani.vibra.core.logger

import java.util.Locale

data class SensorData(
    val tripDistance: Float = 0.0f,      // dalam meter dari firmware
    val staMajor: Int = 0,
    val staMinor: Int = 0,
    val meterRemainder: Int = 0,
    val speed: Float = 0.0f,             // dalam m/s dari firmware
    val elevation: Float = 0.0f,         // dalam m/s²
    val battery: Float = 0.0f,
    val sessionId: Int = 0,
    val packetCount: Int = 0,
    val errorCount: Int = 0,
    val totalOdo: Float = 0.0f,          // total odometer dalam meter
    val wheelCircumference: Float = 2.0f, // wheel circumference dalam meter
    val zOffset: Float = 0.0f,           // Z offset dalam m/s²
    val speedAlpha: Float = 0.25f,       // smoothing alpha
    val smoothingWindow: Int = 5,        // smoothing window size
    val state: String = "READY",
    val rawData: String = "",
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<SensorData> {
    override fun compareTo(other: SensorData): Int {
        return this.timestamp.compareTo(other.timestamp)
    }

    fun formatTripDistance(): String {
        return String.format(Locale.getDefault(), "%.3f km", tripDistance / 1000)
    }

    fun formatSTA(): String {
        return String.format(Locale.getDefault(), "STA %02d+%03d", staMajor, staMinor)
    }

    // Konversi m/s ke km/h
    fun formatSpeed(): String {
        val speedKmh = speed * 3.6f
        return String.format(Locale.getDefault(), "%.1f km/h", speedKmh)
    }

    // Kecepatan asli dalam m/s (untuk debug)
    fun formatSpeedRaw(): String {
        return String.format(Locale.getDefault(), "%.1f m/s", speed)
    }

    fun formatElevation(): String {
        return String.format(Locale.getDefault(), "%.2f m/s²", elevation)
    }

    fun formatBattery(): String {
        return String.format(Locale.getDefault(), "%.2f V", battery)
    }

    fun formatTotalOdo(): String {
        return String.format(Locale.getDefault(), "%.1f m", totalOdo)
    }

    fun formatWheel(): String {
        return String.format(Locale.getDefault(), "%.3f m", wheelCircumference)
    }

    fun formatZOffset(): String {
        return String.format(Locale.getDefault(), "%.3f m/s²", zOffset)
    }
}