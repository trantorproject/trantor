package dev.botta.trantor.ai.schemas

import dev.botta.json.values.JsonValue

/**
 * What a provider holds a model to in strict mode, besides the shape every strict schema has. [StrictSchema] keeps a
 * keyword only when the provider takes it, and says any other in the description: a list of what is taken, not of
 * what is not, so a keyword nobody thought of never turns a call down.
 */
internal open class StrictRules(
    /** The keywords it takes besides the ones of the shape, like `pattern` or `minimum`. */
    private val keywords: Set<String>,
    /** The formats of a string it takes. */
    private val formats: Set<String>,
    /** The largest `minItems` it takes; null when it takes any. */
    private val largestMinItems: Int? = null,
    /** Whether a schema may refer to itself. */
    val takesRecursion: Boolean,
) {
    fun takes(keyword: String, value: JsonValue): Boolean = when (keyword) {
        in SHAPE -> true
        !in keywords -> false
        "format" -> value.asString() in formats
        "minItems" -> largestMinItems == null || (value.asInt() ?: Int.MAX_VALUE) <= largestMinItems
        else -> true
    }

    private companion object {
        /** What every strict schema is made of, which both providers take. */
        val SHAPE = setOf(
            "type",
            "description",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "anyOf",
            "enum",
            "const",
            $$"$ref",
            $$"$defs",
        )
    }
}
