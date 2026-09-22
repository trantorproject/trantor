package dev.botta.trantor.ai.tools

/** What a failure of a tool does to the run. */
enum class ToolErrorModes {
    /** The model is told the call failed and the run goes on. The exception stays in the step. */
    SendToModel,

    /** The run fails with the exception of the tool, for a failure no answer of the model can make right. */
    FailRun,
}
