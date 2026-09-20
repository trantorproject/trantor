package dev.botta.trantor.ai.models.middleware

import dev.botta.trantor.ai.errors.ProviderError
import dev.botta.trantor.ai.errors.RateLimitError
import dev.botta.trantor.ai.errors.TimeoutError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.primitives.logging.getLogger
import java.io.IOException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Tries again when the provider says the problem is temporary: a rate limit, a 5xx, a timeout or a connection that
 * broke. Anything else — a bad api key, a request the provider rejected, a body that did not parse, a bug — fails
 * on the first try, because trying it again only costs time. What counts as temporary is the adapter's call, said
 * in [ProviderError.retryable].
 *
 * The timeout of the call is a deadline for the whole thing and not for each attempt: asking for an answer within
 * thirty seconds should not end up taking ninety. Each attempt gets what is left of it.
 *
 * A stream is opened again only while nothing has come out of it. Once the caller read a part the answer already
 * started, and asking again would give them the beginning twice.
 */
class RetryMiddleware(
    private val maxAttempts: Int = 3,
    private val initialDelay: Duration = 500.milliseconds,
    private val maxDelay: Duration = 30.seconds,
    private val multiplier: Double = 2.0,
    // Spreads out the retries of everyone who got rate limited at the same moment
    private val jitter: Double = 0.2,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
): ChatModelMiddleware {
    override fun generate(
        request: ChatRequest,
        options: CallOptions,
        next: (ChatRequest, CallOptions) -> ChatResponse,
    ): ChatResponse {
        val deadline = Deadline.of(options)

        return retrying(deadline) { next(request, deadline.applyTo(options)) }
    }

    override fun stream(
        request: ChatRequest,
        options: CallOptions,
        next: (ChatRequest, CallOptions) -> ChatStream,
    ): ChatStream {
        val deadline = Deadline.of(options)

        return RetryingStream(deadline) { next(request, deadline.applyTo(options)) }
    }

    private fun <T> retrying(deadline: Deadline, attempt: () -> T): T {
        var tried = 0

        while (true) {
            try {
                return attempt()
            } catch (e: Throwable) {
                tried++

                if (!worthAnotherTry(e, tried, deadline)) throw e

                waitAfter(e, tried)
            }
        }
    }

    private fun worthAnotherTry(error: Throwable, tried: Int, deadline: Deadline): Boolean {
        if (tried >= maxAttempts || !isTemporary(error)) return false

        return deadline.leaves(waitFor(error, tried))
    }

    private fun isTemporary(error: Throwable) = when (error) {
        is TimeoutError -> true
        is ProviderError -> error.retryable
        // A model that does not wrap what the network threw at it still deserves another try
        is IOException -> true
        else -> false
    }

    private fun waitAfter(error: Throwable, attempt: Int) {
        val wait = waitFor(error, attempt)

        logger.warn("Attempt $attempt failed with ${error.message}. Trying again in $wait")
        sleep(wait)
    }

    /** What the provider asked for wins over the backoff: it knows better when it will take the call. */
    private fun waitFor(error: Throwable, attempt: Int): Duration {
        (error as? RateLimitError)?.retryAfter?.let { return minOf(it, maxDelay) }

        val backoff = initialDelay * multiplier.pow(attempt - 1)
        val spread = if (jitter > 0) 1 + Random.nextDouble(-jitter, jitter) else 1.0

        return minOf(backoff * spread, maxDelay)
    }

    /** How much of the timeout of the call is left, and whether there is any point in waiting before trying again. */
    private class Deadline(private val startedAt: TimeSource.Monotonic.ValueTimeMark?, private val total: Duration?) {
        val remaining get() = total?.let { it - (startedAt?.elapsedNow() ?: Duration.ZERO) }

        /** Whether waiting that long would still leave time to try. */
        fun leaves(wait: Duration) = remaining?.let { it > wait } ?: true

        fun applyTo(options: CallOptions) = remaining?.let { options.copy(timeout = it) } ?: options

        companion object {
            fun of(options: CallOptions) =
                Deadline(options.timeout?.let { TimeSource.Monotonic.markNow() }, options.timeout)
        }
    }

    /**
     * A stream that can still be opened again. It is opened right away, so that a provider saying no is an error
     * where the call was made and not later. [started] turns true as soon as a part reached the caller.
     */
    private inner class RetryingStream(private val deadline: Deadline, private val open: () -> ChatStream): ChatStream {
        private var delegate: ChatStream? = retrying(deadline) { open() }
        private var started = false

        override fun hasNext() = read { it.hasNext() }

        override fun next() = read { it.next() }.also { started = true }

        override fun response() = read { it.response() }

        override fun close() {
            delegate?.close()
        }

        private fun <T> read(operation: (ChatStream) -> T): T {
            var tried = 0

            while (true) {
                try {
                    return operation(delegate ?: open().also { delegate = it })
                } catch (e: Throwable) {
                    tried++

                    if (started || !worthAnotherTry(e, tried, deadline)) throw e

                    waitAfter(e, tried)
                    delegate?.let { runCatching { it.close() } }
                    delegate = null
                }
            }
        }
    }

    companion object {
        private val logger = getLogger<RetryMiddleware>()
    }
}
