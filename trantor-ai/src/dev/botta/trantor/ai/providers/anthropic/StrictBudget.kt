package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue

/**
 * How much of one call Anthropic holds a model to, adding up every schema that goes strict: the answer in JSON and
 * the tools, the deferred ones of its tool search included. Past 20 strict tools, or 16 parameters that can be null
 * (a union, written as `anyOf` or as a list of types), it answers 400 to the whole call: see "Schema complexity
 * limits" in https://platform.claude.com/docs/en/build-with-claude/structured-outputs.
 *
 * Its third limit, 24 optional parameters, never adds up here: a schema is closed with every property required. And
 * the size of the grammar has limits of its own with no number to check beforehand, which still fail the call.
 */
internal class StrictBudget {
    private var tools = 0
    private var unions = 0

    /** Counts the answer in JSON, which goes strict regardless: there is no answer in JSON to fall back to. */
    fun spend(schema: JsonObject) {
        unions += unionsIn(schema)
    }

    /** Takes a tool whose closed schema is [schema] when it still fits, and says whether it did. */
    fun take(schema: JsonObject): Boolean {
        val needed = unionsIn(schema)
        if (tools == MAX_TOOLS || unions + needed > MAX_UNIONS) return false

        tools++
        unions += needed

        return true
    }

    /** The parameters whose schema is a union, at any depth: the ones of a nested object count too. */
    private fun unionsIn(value: JsonValue?): Int = when (value) {
        is JsonObject -> (value["properties"]?.asObject()?.values?.count { isUnion(it) } ?: 0) +
            value.values.sumOf { unionsIn(it) }
        is JsonArray -> value.sumOf { unionsIn(it) }
        else -> 0
    }

    private fun isUnion(schema: JsonValue) =
        (schema as? JsonObject)?.let { it.containsKey("anyOf") || it["type"] is JsonArray } ?: false

    companion object {
        const val MAX_TOOLS = 20
        const val MAX_UNIONS = 16
    }
}
