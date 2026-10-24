package com.pure.bubble

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** يسجّل أي انهيار بملف، وتطبيقنا يعرضه بالفتح الجاي حتى نعرف السبب بدقة. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText("thread=${t.name}\n$sw")
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
