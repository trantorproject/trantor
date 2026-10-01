package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.*
import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpStreamResponse
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.time.Duration.Companion.seconds

/** Turns an error of the OpenAI Responses API into the corresponding [AIError]. */
internal class OpenAIErrorMapper {
    fun toError(response: HttpStreamResponse, body: String): AIError {
        val error = runCatching { Json.parse(body).asObject()?.get("error")?.asObject() }.getOrNull()
        val message = error?.get("message")?.asString() ?: "OpenAI returned ${response.status}: $body"
        val code = error?.get("code")?.asString() ?: error?.get("type")?.asString()
        val parameter = error?.get("param")?.asString()
        val provider = OPENAI_PROVIDER

        return when {
            response.status == 401 || response.status == 403 ->
                AuthenticationError(provider, message, response.status, code)
            response.status == 429 -> RateLimitError(provider, retryAfter(response), message, response.status, code)
            response.status == 400 && isContextLength(code, message) ->
                ContextLengthExceededError(provider, message, response.status, code)
            response.status >= 500 -> ProviderUnavailableError(provider, message, response.status, code)
            else -> ProviderError(provider, message, response.status, code, retryable = false, parameter = parameter)
        }
    }

    /**
     * Only what happened on the way to OpenAI is worth trying again. Anything else — a body that did not parse,
     * a bug of ours — is going to fail the same way on the next attempt, so it comes back as an error nobody
     * retries, instead of looking like the provider being down.
     */
    fun toError(error: Throwable): AIError {
        if (error is AIError) return error
        if (hasCause<InterruptedIOException>(error)) return TimeoutError(error.message ?: "The call timed out", error)

        if (hasCause<IOException>(error) || error is HttpClientError) {
            return ProviderUnavailableError(OPENAI_PROVIDER, error.message ?: "Could not reach OpenAI", cause = error)
        }

        return ProviderError(
            OPENAI_PROVIDER,
            error.message ?: "The call to OpenAI failed",
            retryable = false,
            cause = error,
        )
    }

    private fun retryAfter(response: HttpStreamResponse) =
        response.headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
            ?.value?.toDoubleOrNull()?.seconds

    private fun isContextLength(code: String?, message: String) =
        code == "context_length_exceeded" || message.contains("context length", ignoreCase = true)

    private inline fun <reified T> hasCause(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }
}
