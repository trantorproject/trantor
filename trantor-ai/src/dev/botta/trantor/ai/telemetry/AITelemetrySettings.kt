package dev.botta.trantor.ai.telemetry

import dev.botta.env.Env

/**
 * What the traces of the models carry beyond how each call went. Read from the `ai.telemetry` section by `addAI`,
 * and changed in code with `services.configure<AITelemetrySettings> { settings, _ -> ... }`.
 */
data class AITelemetrySettings(
    /**
     * Whether the spans carry what was said: the instructions, the messages that went to the model and the ones that
     * came back, the tools it could call, and the args and results of the tools that ran. Off by default, as the
     * conventions ask, because the messages carry the data of the users and weigh much more than a span; turn it on
     * where the backend of the traces may keep them. It follows `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT`
     * when the configuration does not say.
     */
    var captureContent: Boolean = Env["OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT"]?.toBoolean() ?: false,
    /**
     * The longest a text of the content may be, in characters. A longer one is cut and marked with `…`, and the JSON
     * around it stays whole. Null does not cut, which a backend may do on its own, less kindly, past its limits.
     */
    var maxContentLength: Int? = null,
)
