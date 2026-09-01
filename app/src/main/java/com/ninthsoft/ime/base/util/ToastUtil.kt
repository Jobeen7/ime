package com.ninthsoft.ime.base.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object ToastUtil {

    private val handler = Handler(Looper.getMainLooper())

    internal fun showToast(msg: String) {
        if (msg.isBlank()) return
        handler.post {
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun showToast(context: Context, msg: String) {
        if (msg.isBlank()) return
        handler.post {
            Toast.makeText(
                context.applicationContext, msg, Toast.LENGTH_SHORT
            ).show()
        }
    }
}