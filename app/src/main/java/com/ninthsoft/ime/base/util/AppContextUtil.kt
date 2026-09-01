package com.ninthsoft.ime.base.util

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val appContext: Context get() = ImeApplication.getInstance().applicationContext

fun Context.toast(
    string: String,
    duration: Int = Toast.LENGTH_SHORT,
) {
    Toast.makeText(this, string, duration).show()
}

fun Context.toast(
    @StringRes resId: Int,
    duration: Int = Toast.LENGTH_SHORT,
) {
    Toast.makeText(this, resId, duration).show()
}

fun Context.toast(
    t: Throwable,
    duration: Int = Toast.LENGTH_SHORT,
) {
    toast(t.localizedMessage ?: t.stackTraceToString(), duration)
}

suspend fun <T> Context.toast(
    result: Result<T>,
    duration: Int = Toast.LENGTH_SHORT,
) {
    withContext(Dispatchers.Main.immediate) {
        result.onSuccess { toast(R.string.done, duration) }.onFailure { toast(it, duration) }
    }
}
