package dev.botta.trantor.ai.providers

import dev.botta.trantor.ai.models.chat.ChatModel

/**
 * A provider that Trantor can build models from. It is what [dev.botta.trantor.ai.models.ModelRegistry] looks up
 * by name, and what an application registers when it wants OpenAI, Anthropic or a gateway available.
 *
 * A model id is not validated against anything: it goes to the provider as it came, so a model that came out today
 * works without a release of Trantor. Whether it exists is something the provider answers.
 */
interface AIProvider {
    val name: String

    fun chatModel(modelId: String): ChatModel
}
