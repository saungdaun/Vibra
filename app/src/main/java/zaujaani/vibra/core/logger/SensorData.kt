package zaujaani.vibra.core.logger

import java.util.Locale

data class SensorData(
    val tripDistance: Float = 0.0f,
    val staMajor: Int = 0,
    val staMinor: Int = 0,
    val meterRemainder: Int = 0,
    val speed: Float = 0.0f,
    val elevation: Float = 0.0f,
    val battery: Float = 0.0f,
    val sessionId: Int = 0,
    val packetCount: Int = 0,
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

    fun formatSpeed(): String {
        return String.format(Locale.getDefault(), "%.1f km/h", speed)
    }

    fun formatElevation(): String {
        return String.format(Locale.getDefault(), "%.2f m", elevation)
    }

    fun formatBattery(): String {
        return String.format(Locale.getDefault(), "%.2f V", battery)
    }
}