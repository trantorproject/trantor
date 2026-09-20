package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.errors.CancelledError
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit cancellation token. Cancelling closes the underlying HTTP call, so a running generation stops.
 * Interrupting the thread has the same effect: adapters treat it as a cancellation.
 */
class Cancellation {
    private val cancelled = AtomicBoolean(false)
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled get() = cancelled.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return

        callbacks.forEach { it() }
        callbacks.clear()
    }

    /** Runs the callback when cancelled, or right away if it already was. Close the result to unregister. */
    fun onCancel(callback: () -> Unit): AutoCloseable {
        if (isCancelled) {
            callback()
            return AutoCloseable { }
        }

        callbacks.add(callback)
        return AutoCloseable { callbacks.remove(callback) }
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CancelledError()
    }
}
