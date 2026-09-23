package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import kotlinx.serialization.KSerializer

/**
 * The tool an agent in [OutputMode.Tool] answers with: its args are the object. Calling it right ends the run, once
 * the other calls of its step ran; args that do not fit go back to the model like those of any tool, so it can fix
 * them.
 *
 * The name and what it answers are the ones of PydanticAI, whose default this is.
 */
internal class OutputTool<T: Any>(serializer: KSerializer<T>): Tool<T>(serializer) {
    override val name = NAME
    override val description = "Gives the final answer, as its arguments. Call it once you have everything the answer needs."
    override val readOnly = true

    // The result stays in the conversation, which is why the call gets one: a call without it is refused next time
    override fun execute(args: T, context: ToolContext) = ToolResult.text("Final result processed.")

    companion object {
        const val NAME = "final_result"
    }
}
