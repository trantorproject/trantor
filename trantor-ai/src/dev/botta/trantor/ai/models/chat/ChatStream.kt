package dev.botta.trantor.ai.models.chat

/**
 * A generation being received. It is a blocking pull iterator, meant to be used with use {}: reading blocks the
 * virtual thread, backpressure comes for free and closing cancels the call.
 */
interface ChatStream: Iterator<StreamPart>, AutoCloseable {
    /** The whole response. Consumes what is left of the stream if it was not read. */
    fun response(): ChatResponse
}
