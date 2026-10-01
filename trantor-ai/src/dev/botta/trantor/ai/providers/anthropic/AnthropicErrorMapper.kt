package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.*
import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpStreamResponse
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.time.Duration.Companion.seconds

/** Turns an error of the Anthropic Messages API into the corresponding [AIError]. */
internal class AnthropicErrorMapper {
    fun toError(response: HttpStreamResponse, body: String): AIError {
        val error = runCatching { Json.parse(body).asObject()?.get("error")?.asObject() }.getOrNull()
        val message = error?.get("message")?.asString() ?: "Anthropic returned ${response.status}: $body"
        // Anthropic names the kind of error and not the parameter that caused it, so there is nothing to fill
        // ProviderError.parameter with: what went wrong is only in the message
        val code = error?.get("type")?.asString()
        val provider = ANTHROPIC_PROVIDER

        return when {
            response.status == 401 || response.status == 403 ->
                AuthenticationError(provider, message, response.status, code)
            response.status == 429 -> RateLimitError(provider, retryAfter(response), message, response.status, code)
            isContextLength(response.status, code, message) ->
                ContextLengthExceededError(provider, message, response.status, code)
            // 529 is Anthropic being overloaded, which is a wait and not a bad request
            response.status >= 500 -> ProviderUnavailableError(provider, message, response.status, code)
            else -> ProviderError(provider, message, response.status, code, retryable = false)
        }
    }

    /**
     * Only what happened on the way to Anthropic is worth trying again. Anything else — a body that did not parse,
     * a bug of ours — is going to fail the same way on the next attempt, so it comes back as an error nobody
     * retries, instead of looking like the provider being down.
     */
    fun toError(error: Throwable): AIError {
        if (error is AIError) return error
        if (hasCause<InterruptedIOException>(error)) return TimeoutError(error.message ?: "The call timed out", error)

        if (hasCause<IOException>(error) || error is HttpClientError) {
            val message = error.message ?: "Could not reach Anthropic"
            return ProviderUnavailableError(ANTHROPIC_PROVIDER, message, cause = error)
        }

        return ProviderError(
            ANTHROPIC_PROVIDER,
            error.message ?: "The call to Anthropic failed",
            retryable = false,
            cause = error,
        )
    }

    private fun retryAfter(response: HttpStreamResponse) =
        response.headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
            ?.value?.toDoubleOrNull()?.seconds

    /**
     * A conversation that does not fit comes back as a plain invalid request, so the message is what tells it
     * apart. 413 is the same thing measured in bytes instead of tokens.
     */
    private fun isContextLength(status: Int, code: String?, message: String) = when {
        status == 413 || code == "request_too_large" -> true
        status != 400 -> false
        else -> message.contains("prompt is too long", ignoreCase = true) ||
            message.contains("context window", ignoreCase = true)
    }

    private inline fun <reified T> hasCause(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }
}
