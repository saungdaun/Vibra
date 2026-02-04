package zaujaani.vibra.core.exception

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.Thread.UncaughtExceptionHandler

object AppExceptionHandler : UncaughtExceptionHandler {

    private val TAG = "AppExceptionHandler"
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.d(TAG, "Exception handler initialized")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e(TAG, "Uncaught exception in thread: ${thread.name}", throwable)

            val stackTrace = throwable.stackTrace.joinToString("\n")
            Log.e(TAG, "Stack trace:\n$stackTrace")

            throwable.cause?.let { cause ->
                Log.e(TAG, "Caused by:", cause)
            }

            if (thread == Looper.getMainLooper().thread) {
                showErrorMessageToUser(throwable)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in exception handler", e)
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun showErrorMessageToUser(throwable: Throwable) {
        mainHandler.post {
            try {
                Log.e(TAG, "Showing error to user: ${throwable.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show error dialog", e)
            }
        }
    }
}