package zaujaani.vibra.core.exception

import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.lang.Thread.UncaughtExceptionHandler

object AppExceptionHandler : UncaughtExceptionHandler {

    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        Timber.tag("AppExceptionHandler").i("Exception handler initialized")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Timber.e(throwable, "Uncaught exception in thread: ${thread.name}")

            val stackTrace = throwable.stackTrace.joinToString("\n")
            Timber.e("Stack trace:\n$stackTrace")

            throwable.cause?.let { cause ->
                Timber.e(cause, "Caused by")
            }

            if (thread == Looper.getMainLooper().thread) {
                showErrorMessageToUser(throwable)
            }

        } catch (e: Exception) {
            Timber.e(e, "Error in exception handler")
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun showErrorMessageToUser(throwable: Throwable) {
        mainHandler.post {
            try {
                Timber.e("Showing error to user: ${throwable.message}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to show error dialog")
            }
        }
    }
}