package com.ninthsoft.ime.engine.event


sealed class EngineEvent {
    data class DepolyEvent(val state: State,var schemaIds:List<String>) : EngineEvent() {
        enum class State { Success, Fail }
    }
}