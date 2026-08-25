package com.example.dsh.dsh

/**
 * Small lifecycle gate for asynchronous engine and tunnel callbacks.
 * The native managers keep their own generations; this gate protects the
 * page from callbacks belonging to a previous mode or connection attempt.
 */
internal class DshConnectionCoordinator {
    private var generation = 0L
    private var activeMode: DshConnectionMode? = null

    fun begin(mode: DshConnectionMode): Long {
        activeMode = mode
        generation += 1
        return generation
    }

    fun stop() {
        generation += 1
        activeMode = null
    }

    fun accepts(callbackGeneration: Long, callbackMode: DshConnectionMode): Boolean =
        callbackGeneration == generation && activeMode == callbackMode

    fun isActive(mode: DshConnectionMode): Boolean = activeMode == mode

    fun activeModeOr(fallback: DshConnectionMode): DshConnectionMode = activeMode ?: fallback
}
