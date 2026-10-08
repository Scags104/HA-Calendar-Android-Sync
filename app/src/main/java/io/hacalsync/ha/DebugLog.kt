package io.hacalsync.ha

import android.util.Log

/**
 * Logging is off in release builds so nothing about the user's server or
 * calendars ends up in logcat. Enabled automatically for debuggable builds.
 */
object DebugLog {
    @Volatile
    var enabled = false
    private const val TAG = "HaCalSync"

    fun w(message: String) {
        if (enabled) Log.w(TAG, message)
    }

    fun e(message: String, t: Throwable? = null) {
        if (enabled) Log.e(TAG, message, t)
    }
}
