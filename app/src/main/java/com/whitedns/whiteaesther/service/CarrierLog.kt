package com.whitedns.whiteaesther.service

import android.util.Log
import com.whitedns.whiteaesther.BuildConfig

/**
 * Writes to logcat only in a debug build.
 *
 * [EngineLog] holds the app's own log in memory and mirrors it to logcat only
 * when somebody is watching, and says so in the comment above the check. The
 * carrier processes wrote unconditionally, so a release build was publishing
 * their notices to wherever the device sends logcat: Psiphon's raw tunnel-core
 * messages, which carry the server it dialled and the country it believes the
 * phone is in, plus the bridge transport name and listen address.
 *
 * Logcat is UID-scoped, so this is not another app reading the log. It is the
 * OEM's bug reporter, anyone with `adb logcat`, and a rooted device -- which
 * for a tool used in hostile places is the same audience its diagnostics
 * toggle is careful about, and the reason that toggle exists.
 *
 * runCatching because under JVM unit tests `Log` is a stub that throws.
 */
internal fun debugLog(tag: String, priority: Int, message: String) {
    if (BuildConfig.DEBUG) {
        runCatching { Log.println(priority, tag, message) }
    }
}
