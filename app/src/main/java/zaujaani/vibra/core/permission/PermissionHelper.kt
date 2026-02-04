package zaujaani.vibra.core.permission

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class PermissionHelper(private val fragment: Fragment) {

    private val requestPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        onPermissionResult?.invoke(allGranted)
    }

    var onPermissionResult: ((Boolean) -> Unit)? = null

    fun checkBluetoothPermissions(): Boolean {
        return PermissionManager.requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(
                fragment.requireContext(),
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestBluetoothPermissions() {
        val missingPermissions = PermissionManager.requiredPermissions()
            .filter { permission ->
                ContextCompat.checkSelfPermission(
                    fragment.requireContext(),
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions)
        } else {
            onPermissionResult?.invoke(true)
        }
    }

    fun hasBluetoothConnectPermission(): Boolean {
        return PermissionManager.hasBluetoothConnectPermission(fragment.requireContext())
    }

    fun hasBluetoothScanPermission(): Boolean {
        return PermissionManager.hasBluetoothScanPermission(fragment.requireContext())
    }
}