package dev.botta.trantor.ai.generation

/**
 * The object a run was asked for, or why it did not come: the model refused, ran out of tokens or wrote something
 * that is not the type. For whoever prefers deciding what to do over catching an exception.
 */
data class ObjectResult<T>(
    /** Null when the object did not come, and then [error] says why. */
    val value: T?,
    val run: RunResult,
    val error: NoObjectGeneratedError? = null,
) {
    val finishReason get() = run.finishReason

    val refusal get() = run.response.refusal

    fun getOrThrow(): T = value ?: throw error ?: IllegalStateException("There is no object and no error")
}
