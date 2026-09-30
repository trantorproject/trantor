package dev.botta.trantor.primitives.telemetry

/**
 * Takes out of a URL what must not reach a trace, as the OpenTelemetry conventions ask of `url.full` and
 * `url.query`: the credentials in the authority become `REDACTED:REDACTED`, and the values of the query keys that
 * carry signatures become `REDACTED`. Shared by the spans of the HTTP server and of the HTTP client.
 */
object UrlRedaction {
    /** The keys the conventions list, matched with their case, as they ask. */
    val SENSITIVE_QUERY_KEYS = setOf(
        "X-Amz-Signature", "X-Amz-Credential", "X-Amz-Security-Token", "AWSAccessKeyId", "Signature", "sig",
        "X-Goog-Signature",
    )

    /** The query with the values of the keys the conventions list, and of [secretKeys], redacted. */
    fun query(query: String, secretKeys: Set<String> = emptySet()) = query.split("&").joinToString("&") { pair ->
        val key = pair.substringBefore("=")
        if ("=" in pair && (key in SENSITIVE_QUERY_KEYS || key in secretKeys)) "$key=$REDACTED" else pair
    }

    fun url(url: String): String {
        val afterScheme = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return url
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), afterScheme).takeIf { it >= 0 } ?: url.length
        val authority = url.substring(afterScheme, authorityEnd)
        val withoutCredentials = if ("@" in authority) {
            val credentials = if (":" in authority.substringBeforeLast("@")) "$REDACTED:$REDACTED" else REDACTED
            url.substring(0, afterScheme) + credentials + "@" + authority.substringAfterLast("@") +
                url.substring(authorityEnd)
        } else {
            url
        }

        val queryStart = withoutCredentials.indexOf('?').takeIf { it >= 0 } ?: return withoutCredentials
        val queryEnd = withoutCredentials.indexOf('#', queryStart).takeIf { it >= 0 } ?: withoutCredentials.length
        return withoutCredentials.substring(0, queryStart + 1) +
            query(withoutCredentials.substring(queryStart + 1, queryEnd)) +
            withoutCredentials.substring(queryEnd)
    }

    const val REDACTED = "REDACTED"
}
