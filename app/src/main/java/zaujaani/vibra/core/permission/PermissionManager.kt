package zaujaani.vibra.core.permission

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionManager {

    fun requiredPermissions(): Array<String> {

        val list = mutableListOf<String>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {

            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)

        } else {

            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        return list.toTypedArray()
    }


    fun hasAll(activity: Activity): Boolean {

        return requiredPermissions().all {

            ContextCompat.checkSelfPermission(
                activity,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }


    fun request(activity: Activity) {

        ActivityCompat.requestPermissions(
            activity,
            requiredPermissions(),
            999
        )
    }
}

