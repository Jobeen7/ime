package com.jobeen.ime.input.pinner

import android.content.Context
import android.view.View
import com.jobeen.ime.engine.data.EngineMessage

interface IPinner {
    val view: View
    fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>)
    fun refreshTheme(context: Context)
}
