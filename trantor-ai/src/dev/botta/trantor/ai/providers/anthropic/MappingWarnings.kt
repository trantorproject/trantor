package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.models.ModelWarning

/**
 * What a mapping had to change or leave out on the way, which the response carries so that nothing is lost in
 * silence. It belongs to one mapping: a model is shared by everyone who asks the registry for it.
 */
internal class MappingWarnings(private val modelId: String, private val isGuess: Boolean) {
    private val warnings = mutableListOf<ModelWarning>()

    fun add(warning: ModelWarning) {
        warnings.add(warning)
    }

    fun droppedByTheModel(setting: String) =
        add(ModelWarning("$modelId does not take $setting, so it was not sent$becauseItIsAGuess", setting))

    fun unsupportedSetting(setting: String) =
        add(ModelWarning("The Anthropic Messages API does not support $setting", setting))

    /**
     * A decision taken from a guess says so. The catalog is standing in the newest model it knows for one nobody
     * described, which is right far more often than not and wrong in a way a written entry never is.
     */
    val becauseItIsAGuess
        get() = if (!isGuess) "" else
            ". That is what the newest model in the catalog takes; add $modelId to it if it takes more"

    fun isNotEmpty() = warnings.isNotEmpty()

    fun toList() = warnings.toList()
}
