package com.jobeen.ime.engine.behavior

abstract class InputKey(open val code: Int, open val modifiers: Int, open val isVirtual: Boolean) : IBehavior {}