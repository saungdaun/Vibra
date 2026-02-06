package zaujaani.vibra

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import timber.log.Timber
import zaujaani.vibra.core.exception.AppExceptionHandler

class VibraApplication : Application() {

    companion object {
        lateinit var instance: VibraApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()

        instance = this

        // Force light mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // Setup Timber
        setupTimber()

        // Setup exception handler
        AppExceptionHandler.initialize()

        Timber.tag("VibraApplication").i("✅ Application initialized")
    }

    private fun setupTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTreeWithLineNumbers())
            Timber.tag("VibraApplication").d("🌲 Timber DebugTree planted")
        } else {
            Timber.plant(ReleaseTree())
            Timber.tag("VibraApplication").d("🌲 Timber ReleaseTree planted")
        }
    }

    private class DebugTreeWithLineNumbers : Timber.DebugTree() {
        override fun createStackElementTag(element: StackTraceElement): String? {
            return String.format(
                "%s.%s(%s:%s)",
                super.createStackElementTag(element),
                element.methodName,
                element.fileName,
                element.lineNumber
            )
        }

        override fun isLoggable(tag: String?, priority: Int): Boolean {
            // Di debug mode, log semua level kecuali VERBOSE yang terlalu banyak
            return priority >= Log.DEBUG
        }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Di release, hanya log ERROR dan WARN
            if (priority == Log.ERROR || priority == Log.WARN) {
                // Log ke sistem Android
                Log.println(priority, tag ?: "Vibra", message)
                t?.let {
                    Log.println(priority, tag ?: "Vibra", Log.getStackTraceString(it))
                }
            }
        }

        override fun isLoggable(priority: Int): Boolean {
            // Hanya loggable untuk ERROR dan WARN di release
            return priority == Log.ERROR || priority == Log.WARN
        }
    }
}