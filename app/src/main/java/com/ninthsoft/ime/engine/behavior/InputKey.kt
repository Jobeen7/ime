package com.ninthsoft.ime.engine.behavior

abstract class InputKey(open val code: Int, open val modifiers: Int, open val isVirtual: Boolean) : IBehavior {}