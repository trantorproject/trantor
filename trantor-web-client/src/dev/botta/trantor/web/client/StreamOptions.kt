package dev.botta.trantor.web.client

data class StreamOptions(
    // Maximum time (in milliseconds) the stream can stay silent. Null uses the client idle timeout.
    var readTimeout: Int? = null,
    // Maximum time (in milliseconds) the whole stream can take. Null means no limit.
    var totalTimeout: Int? = null,
)
