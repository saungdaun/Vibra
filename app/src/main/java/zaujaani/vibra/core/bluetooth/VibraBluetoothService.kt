package zaujaani.vibra.core.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import zaujaani.vibra.R

class VibraBluetoothService : Service() {

    override fun onCreate() {
        super.onCreate()

        startForeground(99, notification())

        // boot bluetooth engine sekali
        BluetoothGateway.init(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        return START_STICKY
    }

    private fun notification(): Notification {

        val channelId = "vibra_bt"

        val manager =
            getSystemService(NotificationManager::class.java)

        val channel =
            NotificationChannel(
                channelId,
                "Bluetooth Logger",
                NotificationManager.IMPORTANCE_LOW
            )

        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vibra Logger Running")
            .setContentText("Bluetooth active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
