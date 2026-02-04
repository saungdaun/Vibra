package zaujaani.vibra.core.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import zaujaani.vibra.R

class VibraBluetoothService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 99
        private const val CHANNEL_ID = "vibra_bluetooth_channel"
        private const val CHANNEL_NAME = "Bluetooth Logger Service"
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Buat notification channel
        createNotificationChannel()

        // 2. Buat notification
        val notification = buildNotification()

        // 3. Start foreground service dengan notification
        startForeground(NOTIFICATION_ID, notification)

        // 4. Initialize Bluetooth
        BluetoothGateway.init(applicationContext)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothGateway.cleanup()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background Bluetooth service for Vibra Logger"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vibra Logger")
            .setContentText("Bluetooth service running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}