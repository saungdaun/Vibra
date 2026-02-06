package zaujaani.vibra.core.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import timber.log.Timber

object PermissionManager {

    // ==================== UTILITY FUNCTIONS FOR BOTH ACTIVITY AND FRAGMENT ====================

    fun hasAll(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_ADMIN
                    ) == PackageManager.PERMISSION_GRANTED &&
                    (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED)
        }
    }

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH
                ) == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            Timber.e(e, "Bluetooth connect permission check failed")
            false
        }
    }

    fun hasBluetoothScanPermission(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                val hasBluetoothAdmin = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED

                val hasLocation = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                hasBluetoothAdmin && hasLocation
            }
        } catch (e: Exception) {
            Timber.e(e, "Bluetooth scan permission check failed")
            false
        }
    }

    fun checkBluetoothPermissions(context: Context): Boolean {
        return hasBluetoothConnectPermission(context) && hasBluetoothScanPermission(context)
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    // ==================== ACTIVITY PERMISSION HELPER ====================

    fun hasAll(activity: Activity): Boolean {
        return hasAll(activity as Context)
    }

    fun request(activity: Activity, requestCode: Int = 999) {
        ActivityCompat.requestPermissions(
            activity,
            requiredPermissions(),
            requestCode
        )
    }

    // ==================== FRAGMENT PERMISSION HELPER CLASS ====================

    class FragmentHelper(private val fragment: Fragment) {
        var onPermissionResult: ((Boolean) -> Unit)? = null

        fun checkBluetoothPermissions(): Boolean {
            return PermissionManager.checkBluetoothPermissions(fragment.requireContext())
        }

        fun hasBluetoothConnectPermission(): Boolean {
            return PermissionManager.hasBluetoothConnectPermission(fragment.requireContext())
        }

        fun hasBluetoothScanPermission(): Boolean {
            return PermissionManager.hasBluetoothScanPermission(fragment.requireContext())
        }

        fun requestBluetoothPermissions(requestCode: Int = 100) {
            fragment.requestPermissions(
                PermissionManager.requiredPermissions(),
                requestCode
            )
        }

        fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
        ) {
            if (requestCode == 100) {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                onPermissionResult?.invoke(allGranted)
            }
        }
    }

    // ==================== PERMISSION CHECK RESULT ====================

    fun isAllGranted(
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        return grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }

    fun getMissingPermissions(context: Context): List<String> {
        return requiredPermissions()
            .filter { permission ->
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            }
    }

    // ==================== RATIONALE CHECK ====================

    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    fun shouldShowRationale(fragment: Fragment, permission: String): Boolean {
        return fragment.shouldShowRequestPermissionRationale(permission)
    }
}