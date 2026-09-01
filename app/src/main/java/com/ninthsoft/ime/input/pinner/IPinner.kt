package com.ninthsoft.ime.input.pinner

import android.content.Context
import android.view.View
import com.ninthsoft.ime.engine.data.EngineMessage

interface IPinner {
    val view: View
    fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>)
    fun refreshTheme(context: Context)
}
