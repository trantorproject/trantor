package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.errors.AIError

/** A model reference that the registry cannot turn into a model: unknown provider, unknown alias or a bad reference. */
class ModelNotFoundError(val reference: String, message: String): AIError(message)
