package dev.botta.trantor.ai.schemas

import dev.botta.json.Json
import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject

/**
 * Adapts a JSON Schema to what a provider accepts in strict mode, without touching the original.
 *
 * Strict mode asks for two things the schema of a Kotlin type does not have: every object closed with
 * additionalProperties and **every** property listed in required. A field with a default is optional in Kotlin but
 * has to be required here, which is why an optional field has to be nullable to work: the model answers it as null.
 *
 * Definitions are also renamed to their simple name, since kotlinx generates them with the whole package.
 *
 * Both OpenAI and Anthropic ask for the same two things, so the shape is shared rather than copied. Anthropic is
 * the looser of the two: it only demands `additionalProperties: false`, and takes a listed default in place of a
 * required field. Closing more than it asks for is still a schema it accepts.
 */
internal object StrictSchema {
    fun of(schema: JsonObject): JsonObject {
        val copy = Json.parse(schema.toString()).asObject() ?: return schema

        shortenDefinitionNames(copy)
        close(copy)

        return copy
    }

    private fun shortenDefinitionNames(schema: JsonObject) {
        val definitions = schema[DEFS]?.asObject() ?: return
        val shortNames = definitions.keys.associateWith { it.substringAfterLast(".") }

        if (shortNames.values.distinct().size != shortNames.size) return

        val renamed = Json.obj()
        definitions.keys.toList().forEach { renamed[shortNames.getValue(it)] = definitions.getValue(it) }
        schema[DEFS] = renamed

        shortNames.forEach { (name, shortName) -> rewriteRefs(schema, "$REF_PREFIX$name", "$REF_PREFIX$shortName") }
    }

    private fun rewriteRefs(value: Any?, from: String, to: String) {
        when (value) {
            is JsonObject -> value.keys.toList().forEach { key ->
                val child = value[key]
                if (key == REF && child?.asString() == from) value[key] = to else rewriteRefs(child, from, to)
            }
            is JsonArray -> value.forEach { rewriteRefs(it, from, to) }
        }
    }

    private fun close(value: Any?) {
        when (value) {
            is JsonObject -> {
                val properties = value["properties"]?.asObject()

                if (properties != null) {
                    value["additionalProperties"] = false
                    value["required"] = Json.array(properties.keys.toList())
                }

                value.values.forEach { close(it) }
            }
            is JsonArray -> value.forEach { close(it) }
        }
    }

    private const val DEFS = $$"$defs"
    private const val REF = $$"$ref"
    private const val REF_PREFIX = $$"#/$defs/"
}
