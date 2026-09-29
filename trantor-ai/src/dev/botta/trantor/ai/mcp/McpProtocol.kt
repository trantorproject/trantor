package dev.botta.trantor.ai.mcp

import java.util.Base64

/**
 * What a client and a server of MCP agree on, as the 2026-07-28 revision names it: the versions, the headers of a
 * request over HTTP, the keys of `_meta` and the codes of the errors. The client of trantor-ai and the server of
 * trantor-mcp-server both speak by it; an application does not need it to use either.
 */
object McpProtocol {
    /** The revision this client and the server of Trantor speak, where every request stands on its own. */
    const val VERSION = "2026-07-28"

    /** The last revision with the handshake and the session, which most servers and clients still speak. */
    const val EARLIER_VERSION = "2025-11-25"

    /**
     * The revisions before 2026-07-28 that speak over Streamable HTTP, with a handshake: the ones before them used
     * HTTP+SSE, another transport.
     */
    val EARLIER_VERSIONS = listOf(EARLIER_VERSION, "2025-06-18", "2025-03-26")

    /** The headers a request over HTTP carries, mirroring its body so that a gateway can route without reading it. */
    object Headers {
        const val PROTOCOL_VERSION = "MCP-Protocol-Version"
        const val METHOD = "Mcp-Method"

        /** The name of the tool of a `tools/call`, encoded with [encodeHeaderValue] when it is not plain ASCII. */
        const val NAME = "Mcp-Name"

        /** The session of the revisions before 2026-07-28. */
        const val SESSION = "Mcp-Session-Id"

        /** In front of the name an argument marked with `x-mcp-header` goes by. */
        const val PARAM_PREFIX = "Mcp-Param-"
    }

    /** The keys of `_meta` with which a request of 2026-07-28 says who asks and on which revision. */
    object Meta {
        const val PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
        const val CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
        const val SERVER_INFO = "io.modelcontextprotocol/serverInfo"
    }

    /** The codes of the errors of JSON-RPC, and the ones MCP adds. */
    object Errors {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        /** The headers of a request over HTTP do not match its body. */
        const val HEADER_MISMATCH = -32020

        /** The request needs a capability the client did not declare. */
        const val MISSING_REQUIRED_CLIENT_CAPABILITY = -32021

        /** The server does not speak the revision the request asks for; its data lists the ones it does. */
        const val UNSUPPORTED_PROTOCOL_VERSION = -32022
    }

    private const val BASE64_PREFIX = "=?base64?"
    private const val BASE64_SUFFIX = "?="

    /**
     * [value] as it goes in a header: as it is when it is plain ASCII without spaces around it, and in base64 between
     * `=?base64?` and `?=` when not, or when it would look encoded already.
     */
    fun encodeHeaderValue(value: String): String {
        val looksEncoded = value.startsWith(BASE64_PREFIX) && value.endsWith(BASE64_SUFFIX)
        val plain = value.all { it in ' '..'~' } && value.trim() == value && !looksEncoded

        if (plain) return value

        return BASE64_PREFIX + Base64.getEncoder().encodeToString(value.toByteArray()) + BASE64_SUFFIX
    }

    /** The value a header carries, decoding it when it is in base64; null when its base64 cannot be read. */
    fun decodeHeaderValue(header: String): String? {
        if (!(header.startsWith(BASE64_PREFIX) && header.endsWith(BASE64_SUFFIX))) return header

        val encoded = header.removePrefix(BASE64_PREFIX).removeSuffix(BASE64_SUFFIX)
        return runCatching { String(Base64.getDecoder().decode(encoded)) }.getOrNull()
    }
}
