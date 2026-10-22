package io.github.arttolst12.netion.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnLifecycleDispatchTest {
    @Test
    fun lifecycleActionWaitsForDispatcher() {
        val service = TunVpnService()
        service.tunGeneration.set(10)
        val queue = ArrayDeque<() -> Unit>()
        var calls = 0
        service.postForGeneration(10, dispatch = { queue.addLast(it) }) { calls++ }
        assertEquals(0, calls)
        queue.removeFirst().invoke()
        assertEquals(1, calls)
    }

    @Test
    fun oldRevocationActionCannotAffectReplacementGeneration() {
        val service = TunVpnService()
        service.tunGeneration.set(10)
        val queue = ArrayDeque<() -> Unit>()
        var stopped = false
        service.postForGeneration(10, dispatch = { queue.addLast(it) }) { stopped = true }
        service.tunGeneration.incrementAndGet()
        queue.removeFirst().invoke()
        assertFalse(stopped)
        assertEquals(11, service.tunGeneration.get())
    }

    @Test
    fun newerGenerationActionStillRunsAfterStaleAction() {
        val service = TunVpnService()
        val queue = ArrayDeque<() -> Unit>()
        val actions = mutableListOf<Long>()
        service.tunGeneration.set(10)
        service.postForGeneration(10, dispatch = { queue.addLast(it) }) { actions += 10L }
        service.tunGeneration.set(11)
        service.postForGeneration(11, dispatch = { queue.addLast(it) }) { actions += 11L }
        queue.forEach { it() }
        assertEquals(listOf(11L), actions)
    }
}
