package dev.botta.trantor.ai.errors

/** A model reference that the registry cannot turn into a model: unknown provider, unknown alias or a bad reference. */
class ModelNotFoundError(val reference: String, message: String): AIError(message)
