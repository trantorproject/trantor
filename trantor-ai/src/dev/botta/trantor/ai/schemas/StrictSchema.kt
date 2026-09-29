package dev.botta.trantor.ai.schemas

import dev.botta.json.Json
import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue

/**
 * Adapts a JSON Schema to what a provider accepts in strict mode, without touching the original.
 *
 * Strict mode asks for two things the schema of a Kotlin type does not have: every object closed with
 * additionalProperties and **every** property listed in required. A field with a default is optional in Kotlin but
 * has to be required here, which is why an optional field has to be nullable to work: the model answers it as null.
 *
 * Definitions are also renamed to their simple name, since kotlinx generates them with the whole package, and every
 * `oneOf` becomes an `anyOf`: kotlinx writes a sealed class and a nullable object as `oneOf`, which neither provider
 * takes in strict mode ("'oneOf' is not permitted", "Schema type 'oneOf' is not supported", recorded on 2026-09-29).
 * Their options exclude each other anyway, by the label of each subtype or by being null, so `anyOf` says the same.
 *
 * Both OpenAI and Anthropic ask for that same shape, so it is shared rather than copied. What else each holds a model
 * to differs, and comes in its [StrictRules]: a keyword the provider does not take goes to the description of its
 * field, as the SDKs of Anthropic do (`{minimum: 1, maximum: 10}`), where the model reads it without being held to
 * it. Whoever reads the answer still checks it, as a use case validates its request.
 */
internal object StrictSchema {
    fun of(schema: JsonObject, rules: StrictRules): JsonObject {
        val copy = Json.parse(schema.toString()).asObject() ?: return schema

        shortenDefinitionNames(copy)
        close(copy)
        replaceOneOf(copy)
        restrict(copy, rules)

        return copy
    }

    /**
     * Whether [schema] refers to itself, from the root (`#`, as the serializer writes a class that contains itself) or
     * through its definitions: what Anthropic cannot hold a model to.
     */
    fun refersToItself(schema: JsonObject): Boolean {
        val definitions = schema[DEFS]?.asObject().orEmpty()
        val references = mutableMapOf(ROOT to referencesIn(schema, skip = DEFS))
        definitions.forEach { (name, definition) -> references["$REF_PREFIX$name"] = referencesIn(definition) }

        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()

        fun cycleFrom(node: String): Boolean {
            if (node in visiting) return true
            if (node in done) return false

            visiting += node
            val found = references[node].orEmpty().any { cycleFrom(it) }
            visiting -= node
            done += node

            return found
        }

        return references.keys.any { cycleFrom(it) }
    }

    private fun referencesIn(value: JsonValue?, skip: String? = null): Set<String> = when (value) {
        is JsonObject -> value.entries.filter { it.key != skip }.flatMap { (key, child) ->
            if (key == REF) setOfNotNull(child.asString()) else referencesIn(child)
        }.toSet()
        is JsonArray -> value.flatMap { referencesIn(it) }.toSet()
        else -> emptySet()
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

    private fun replaceOneOf(value: Any?) {
        when (value) {
            is JsonObject -> {
                value.remove(ONE_OF)?.let { value[ANY_OF] = it }
                value.values.forEach { replaceOneOf(it) }
            }
            is JsonArray -> value.forEach { replaceOneOf(it) }
        }
    }

    /**
     * Keeps in [schema] and in the schemas inside it only what [rules] take, and says the rest in the description. It
     * walks the schemas by where they are, so a property called like a keyword (`minimum`) stays a property.
     */
    private fun restrict(schema: JsonObject, rules: StrictRules) {
        val left = schema.entries.filter { (keyword, value) -> !rules.takes(keyword, value) }.map { it.key to it.value }
        left.forEach { (keyword, _) -> schema.remove(keyword) }

        schema["properties"]?.asObject()?.values?.forEach { restrictSchema(it, rules) }
        schema[DEFS]?.asObject()?.values?.forEach { restrictSchema(it, rules) }
        restrictSchema(schema["items"], rules)
        schema[ANY_OF]?.asArray()?.forEach { restrictSchema(it, rules) }
        schema[ALL_OF]?.asArray()?.forEach { restrictSchema(it, rules) }

        if (left.isEmpty()) return

        val said = left.joinToString(", ", "{", "}") { (keyword, value) -> "$keyword: $value" }
        schema["description"] = listOfNotNull(schema["description"]?.asString(), said).joinToString("\n\n")
    }

    private fun restrictSchema(value: JsonValue?, rules: StrictRules) {
        (value as? JsonObject)?.let { restrict(it, rules) }
    }

    private const val ONE_OF = "oneOf"
    private const val ANY_OF = "anyOf"
    private const val ALL_OF = "allOf"
    private const val DEFS = $$"$defs"
    private const val REF = $$"$ref"
    private const val REF_PREFIX = $$"#/$defs/"
    private const val ROOT = "#"
}
