package com.connect.mobile.util

import android.util.Log

/** Debug diagnostics only. NEVER pass tokens, passwords, headers or request bodies. */
fun interface AppLogger {
    fun debug(message: String)

    companion object {
        val NONE = AppLogger { }

        fun android(enabled: Boolean): AppLogger =
            if (enabled) AppLogger { Log.d("CONNECT", it) } else NONE
    }
}

/** Messages of the cause chain, e.g. OkHttp's "CLEARTEXT communication to … not permitted …". */
fun Throwable.causeChainMessages(): String =
    generateSequence(this) { it.cause }.take(8)
        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
