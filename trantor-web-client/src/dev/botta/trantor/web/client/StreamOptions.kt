package dev.botta.trantor.web.client

import dev.botta.trantor.primitives.Cancellation

data class StreamOptions(
    // Maximum time (in milliseconds) the stream can stay silent. Null uses the client idle timeout.
    var readTimeout: Int? = null,
    // Maximum time (in milliseconds) the whole stream can take. Null means no limit.
    var totalTimeout: Int? = null,
    /**
     * Stops the call when cancelled, at any point: while it waits for the headers, which is the whole call when the
     * server answers at once, or while the body is read. A call cut while it waits fails with [HttpClientError], and
     * one cut while it is read ends as [HttpStreamResponse.cancel] ends it. The client stops listening when the
     * response is closed.
     */
    var cancellation: Cancellation? = null,
)
