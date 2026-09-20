package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.Cancellation
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.web.client.HttpStreamResponse

/**
 * Ties an http call to a [Cancellation] for as long as the call lasts.
 *
 * It listens before the call is made, because opening it blocks until the provider answers and that wait has to be
 * cancellable too. When the call is over it stops listening, so that a token reused for many calls does not end up
 * holding a callback for each one.
 *
 * Cancelling closes the connection, which the transport reports as an end of data rather than as an error. That is
 * why what is read afterwards is checked with [throwIfCancelled]: half an answer should not be taken for an answer.
 */
class CancellationLink(cancellation: Cancellation?): AutoCloseable {
    private var response: HttpStreamResponse? = null
    private var cancelled = false

    private val registration = cancellation?.onCancel { cancel() }

    @Synchronized
    fun attach(response: HttpStreamResponse) = response.also {
        if (cancelled) {
            it.cancel()
            throw CancelledError()
        }

        this.response = it
    }

    @Synchronized
    fun throwIfCancelled() {
        if (cancelled) throw CancelledError()
    }

    @Synchronized
    private fun cancel() {
        cancelled = true
        response?.cancel()
    }

    override fun close() {
        registration?.close()
    }
}
