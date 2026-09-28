package com.jobeen.ime.base.util


import com.jobeen.ime.ImeApplication
import kotlinx.coroutines.CoroutineScope

val appScope: CoroutineScope get() = ImeApplication.getInstance().applicationScope
