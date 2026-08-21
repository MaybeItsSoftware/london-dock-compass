package uk.co.maybeitssoftware.londondockcompass

import android.app.Application
import android.os.StrictMode

/**
 * Debug-only. Turns "why does the deck stutter every twenty seconds" into a logcat line.
 *
 * The dock cache is SharedPreferences holding a JSON blob and the offline fallback parses a
 * quarter-megabyte of coordinates; both used to run on the main thread because the repository was
 * `suspend` without ever leaving the caller's dispatcher. Nothing about that was visible until you
 * looked for it, so it is worth failing loudly in development.
 */
class DebugApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }
}
