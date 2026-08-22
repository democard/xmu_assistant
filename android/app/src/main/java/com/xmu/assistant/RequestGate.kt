package com.xmu.assistant

internal class RequestGate {
    private val active = mutableSetOf<String>()

    @Synchronized
    fun tryStart(key: String): Boolean = active.add(key)

    @Synchronized
    fun finish(key: String) {
        active.remove(key)
    }
}
