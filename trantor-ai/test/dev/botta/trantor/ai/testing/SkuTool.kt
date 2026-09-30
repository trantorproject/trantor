package dev.botta.trantor.ai.testing

import dev.botta.json.Json
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.serialization.gson.adapters.StringValueSerializer

/** A tool whose args are a value object of the application, which only the serializer that registered it knows. */
class SkuTool: Tool<SkuTool.Args>() {
    override val name = "stock"
    override val description = "The stock of a product"

    var received: Args? = null

    override fun execute(args: Args, context: ToolContext): ToolResult {
        received = args
        return ToolResult.text("12")
    }

    data class Args(val sku: Sku)

    data class Sku(val value: String)

    companion object {
        const val PATTERN = "^[A-Z]{3}-[0-9]+$"

        /** The serializer of an application that registered [Sku], with the schema of its codes. */
        fun serializer() = GsonSerializer().apply {
            registerTypeAdapter(
                Sku::class.java,
                StringValueSerializer({ Sku(it) }, { it.value }),
                Json.obj("type" to "string", "pattern" to PATTERN),
            )
        }
    }
}
