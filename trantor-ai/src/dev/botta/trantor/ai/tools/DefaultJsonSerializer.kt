package dev.botta.trantor.ai.tools

import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer

/**
 * The serializer of a run that was given none, like a loop an application builds by hand: Gson with the adapters of
 * Trantor, for the types of the domain. One for all of them, since nothing registers on it.
 */
internal val defaultJsonSerializer: JsonSerializer by lazy { GsonSerializer() }
