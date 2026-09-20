package dev.botta.trantor.ai.models

/** Something the adapter could not honor. Ends up in the response, the log and the span, instead of failing silently. */
data class ModelWarning(val message: String, val setting: String? = null)
