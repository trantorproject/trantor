package dev.botta.trantor.primitives

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit cancellation token, for work that runs on a thread of its own and has to be stopped from another.
 *
 * Whatever runs the work listens with [onCancel] and stops it however it can: the http client, for one, cancels its
 * call, even while it waits for the answer. A token is cancelled once and for good, and one token can stop many pieces
 * of work at once.
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

    /**
     * Runs the callback when cancelled, or right away if it already was. Close the result to unregister: a token
     * reused for many pieces of work would otherwise keep a callback for each one.
     */
    fun onCancel(callback: () -> Unit): AutoCloseable {
        if (isCancelled) {
            callback()
            return AutoCloseable { }
        }

        callbacks.add(callback)
        return AutoCloseable { callbacks.remove(callback) }
    }
}
