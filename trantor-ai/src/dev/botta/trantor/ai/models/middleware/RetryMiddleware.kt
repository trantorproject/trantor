package dev.botta.trantor.ai.models.middleware

import dev.botta.trantor.ai.errors.AIError
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.errors.ProviderError
import dev.botta.trantor.ai.errors.RateLimitError
import dev.botta.trantor.ai.errors.TimeoutError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.primitives.logging.getLogger
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tries again when the provider says the problem is temporary: a rate limit, a 5xx or a timeout. Anything else —
 * a bad api key, a request the provider rejected, a cancellation — fails on the first try, because trying it again
 * only costs time.
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
    ) = retrying { next(request, options) }

    override fun stream(
        request: ChatRequest,
        options: CallOptions,
        next: (ChatRequest, CallOptions) -> ChatStream,
    ): ChatStream = RetryingStream { next(request, options) }

    private fun <T> retrying(attempt: () -> T): T {
        var tried = 0

        while (true) {
            try {
                return attempt()
            } catch (e: Throwable) {
                tried++

                if (tried >= maxAttempts || !isTemporary(e)) throw e

                waitAfter(e, tried)
            }
        }
    }

    private fun isTemporary(error: Throwable) = when (error) {
        is CancelledError -> false
        is TimeoutError -> true
        is ProviderError -> error.retryable
        // Anything that is not ours is a connection that broke on the way, which is worth trying again
        else -> error !is AIError
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

    /**
     * A stream that can still be opened again. It is opened right away, so that a provider saying no is an error
     * where the call was made and not later. [started] turns true as soon as a part reached the caller.
     */
    private inner class RetryingStream(private val open: () -> ChatStream): ChatStream {
        private var delegate: ChatStream? = retrying { open() }
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

                    if (started || tried >= maxAttempts || !isTemporary(e)) throw e

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
