package dev.botta.trantor.ai.mcp

import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue

/**
 * The arguments of a tool that a server of 2026-07-28 marks with `x-mcp-header` in its schema, which a client over
 * HTTP also sends as `Mcp-Param-{name}` headers, so that a gateway can route by them without reading the body. The
 * server checks they match the body.
 *
 * The rules of the spec, checked as the server of the TypeScript SDK v2 checks them: the name is an HTTP token,
 * unique whatever the case; the argument is a string, an integer or a boolean; and it is reached from the root only
 * through `properties`, not inside `items`, `oneOf` or `$defs`. That SDK takes a `number` too, since a scenario of the
 * conformance suite uses one; the spec does not, and neither does this client.
 */
internal object McpParamHeaders {
    private const val MARK = "x-mcp-header"
    private const val PREFIX = McpProtocol.Headers.PARAM_PREFIX
    private val TOKEN = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
    private val TYPES = setOf("string", "integer", "boolean")

    /** Where a subschema is not reached through `properties` alone, so a mark in it is not allowed. */
    private val ELSEWHERE = listOf(
        "items", "prefixItems", "contains", "additionalProperties", "unevaluatedProperties", "unevaluatedItems",
        "propertyNames", "patternProperties", "dependentSchemas", "oneOf", "anyOf", "allOf", "not", "if", "then",
        "else", "\$defs", "definitions",
    )

    /** The ones whose value is an object of subschemas by name, and not a subschema or a list of them. */
    private val BY_NAME = setOf("patternProperties", "dependentSchemas", "\$defs", "definitions")

    /** An argument marked for a header: where it is in the arguments, and the name that goes after the prefix. */
    class Declaration(val path: List<String>, val name: String)

    sealed interface Scan {
        class Valid(val declarations: List<Declaration>): Scan

        class Invalid(val reason: String): Scan
    }

    /** The marks of a tool, or the first rule one of them breaks. */
    fun scan(schema: JsonObject): Scan {
        val declarations = mutableListOf<Declaration>()
        val seen = mutableMapOf<String, String>()

        fun visit(node: JsonValue?, path: List<String>, reachable: Boolean): String? {
            val schema = node?.asObject() ?: return null

            schema[MARK]?.let { mark ->
                val where = path.joinToString(".").ifEmpty { "the root" }
                val name = mark.asString()
                val type = schema["type"]?.asString()

                if (!reachable || path.isEmpty()) {
                    return "$where: $MARK is only allowed on a property reached through properties"
                }
                if (name.isNullOrEmpty() || !TOKEN.matches(name)) {
                    return "$where: $MARK '${name ?: mark}' is not an HTTP header name"
                }
                if (type !in TYPES) {
                    return "$where: $MARK is only allowed on a string, an integer or a boolean, not ${type ?: "none"}"
                }
                seen.put(name.lowercase(), name)?.let { return "$MARK '$name' is also declared as '$it'" }

                declarations.add(Declaration(path, name))
            }

            schema["properties"]?.asObject()?.forEach { (key, child) ->
                visit(child, path + key, reachable)?.let { return it }
            }

            for (keyword in ELSEWHERE) {
                val sub = schema[keyword] ?: continue
                val branches = when {
                    sub is JsonArray -> sub.toList()
                    keyword in BY_NAME -> sub.asObject()?.values?.toList().orEmpty()
                    else -> listOf(sub)
                }
                branches.forEach { branch -> visit(branch, path + "<$keyword>", reachable = false)?.let { return it } }
            }

            return null
        }

        return visit(schema, emptyList(), reachable = true)?.let { Scan.Invalid(it) } ?: Scan.Valid(declarations)
    }

    /**
     * The headers of the marked arguments that have a value: a string as it is, an integer in decimal and a boolean
     * in lower case. A null or a missing argument sends none, as the spec says.
     */
    fun of(declarations: List<Declaration>, arguments: JsonObject) = declarations.mapNotNull { declaration ->
        val value = declaration.path.fold(arguments as JsonValue?) { node, key -> node?.asObject()?.get(key) }
        val text = when {
            value == null || value.isNull -> null
            value.isString -> value.asString()
            value.isNumber || value.isBoolean -> value.toString()
            else -> null
        }

        text?.let { PREFIX + declaration.name to encode(it) }
    }.toMap()

    /**
     * A value as a header can carry it: as it is when it is plain ASCII, and in base64 otherwise, in the form the
     * spec defines. That covers a value with other letters, and one with a line break that would add a header.
     */
    fun encode(value: String) = McpProtocol.encodeHeaderValue(value)
}
