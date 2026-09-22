package dev.botta.trantor.ai.tools

import dev.botta.json.values.JsonValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import dev.botta.json.Json as BottaJson
import kotlinx.serialization.json.Json as KotlinxJson

/**
 * What a tool answers, which goes back to the model as the result of its call.
 *
 * A class of its own and not a bare [ToolOutput] so that a tool can later say something for the application apart
 * from what the model reads, without changing the signature of every tool.
 */
data class ToolResult(val output: ToolOutput) {
    companion object {
        fun text(value: String) = ToolResult(ToolOutput.Text(value))

        fun json(value: JsonValue) = ToolResult(ToolOutput.Json(value))

        /** A @Serializable object, as its JSON. */
        fun <T> json(value: T, serializer: KSerializer<T>) =
            json(BottaJson.parse(KotlinxJson.encodeToString(serializer, value)))

        inline fun <reified T> json(value: T) = json(value, serializer<T>())
    }
}
