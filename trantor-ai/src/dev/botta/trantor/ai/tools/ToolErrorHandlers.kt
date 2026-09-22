package dev.botta.trantor.ai.tools

/**
 * The [ToolErrorHandler]s of the application, asked in the order they were added. Registered with
 * `addToolErrorHandlers`, so that reading that call is enough to know what a model may learn about a failure.
 */
class ToolErrorHandlers {
    private val handlers = mutableListOf<ToolErrorHandler>()

    val all: List<ToolErrorHandler>
        @Synchronized get() = handlers.toList()

    @Synchronized
    fun add(handler: ToolErrorHandler) = apply { handlers.add(handler) }
}
