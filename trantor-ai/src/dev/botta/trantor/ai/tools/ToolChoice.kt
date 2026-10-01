package dev.botta.trantor.ai.tools

/** How free the model is to call tools: as it likes ([Auto], the default), not at all, any of them, or one by name. */
sealed interface ToolChoice {
    data object Auto: ToolChoice

    data object None: ToolChoice

    data object Required: ToolChoice

    data class Named(val name: String): ToolChoice
}
