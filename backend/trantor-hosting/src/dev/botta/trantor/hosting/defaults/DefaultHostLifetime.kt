package dev.botta.trantor.hosting.defaults

import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.hosting.HostLifetime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class DefaultHostLifetime: HostLifetime {
    private val logger = getLogger()
    private val startedHandlers = CopyOnWriteArrayList<() -> Unit>()
    private val stoppingHandlers = CopyOnWriteArrayList<() -> Unit>()
    private val stoppedHandlers = CopyOnWriteArrayList<() -> Unit>()
    private val state = AtomicReference(HostState.CREATED)

    override fun onStarted(action: () -> Unit) {
        startedHandlers.add(action)
        if (state.get().hasStarted()) action()
    }

    override fun onStopping(action: () -> Unit) {
        stoppingHandlers.add(action)
        if (state.get().hasRequestedStop()) action()
    }

    override fun onStopped(action: () -> Unit) {
        stoppedHandlers.add(action)
        if (state.get() == HostState.STOPPED) action()
    }

    internal fun notifyStarted() {
        if (!state.compareAndSet(HostState.CREATED, HostState.STARTED)) return
        callHandlers(startedHandlers)
    }

    internal fun notifyStopping() {
        if (!state.compareAndSet(HostState.STARTED, HostState.STOPPING)) return
        callHandlers(stoppingHandlers)
    }

    internal fun notifyStopped() {
        if (!state.compareAndSet(HostState.STOPPING, HostState.STOPPED)) return
        callHandlers(stoppedHandlers)
    }

    private fun callHandlers(handlers: List<() -> Unit>) {
        for (handler in handlers) {
            try {
                handler()
            } catch (e: Exception) {
                logger.error(e.message, e)
            }
        }
    }

    override fun stopApplication() {
        notifyStopping()
    }

    private enum class HostState {
        CREATED,
        STARTED,
        STOPPING,
        STOPPED;

        fun hasStarted(): Boolean =
            this == STARTED || this == STOPPING || this == STOPPED

        fun hasRequestedStop(): Boolean =
            this == STOPPING || this == STOPPED
    }
}
