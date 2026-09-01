package com.ninthsoft.ime.base.once

class Once {
    @Volatile
    private var done = false

    fun invoke(block: () -> Unit) {
        if (!done) {
            synchronized(this) {
                if (!done) {
                    block()
                    done = true
                }
            }
        }
    }
}