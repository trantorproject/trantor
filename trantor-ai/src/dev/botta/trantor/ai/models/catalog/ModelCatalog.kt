package dev.botta.trantor.ai.models.catalog

/**
 * What each model is: what it accepts, and later what it costs.
 *
 * It answers a different question from the registry. *"Can I call this model?"* is always yes — the id is a string
 * and goes as it came. *"What does this model take?"* is this, and an adapter asks it before it fills the body of
 * a request, so that a setting the model would refuse comes back as a warning instead of a failed call.
 *
 * **A model nobody described stands in for the newest one of its provider**, which is what [addDefault] names. A
 * model that comes out is almost always the one before it with something taken away — providers drop a sampling
 * setting, swap a budget for a level — so the newest entry is the closest thing to the truth, and the call goes
 * out working instead of failing on a parameter that generation stopped taking.
 *
 * A spec that came from there is marked [ModelSpec.isGuess], and every decision an adapter makes out of one says
 * so in its warning. That is the price: a guess can drop something the model did accept, which a written entry
 * cannot. Saying it out loud is what keeps it from being silent, and writing the model down is a line.
 *
 * With no default registered, [find] answers null and the adapter sends what it was given, exactly as it would
 * with no catalog at all.
 *
 * ### Keeping it up to date
 *
 * Only the part nobody else maintains lives here by hand, and it is written **per family and not per model**: the
 * whole of Anthropic is nine entries, because `copy` is what a profile looks like in Kotlin. Pricing and context
 * windows are maintained by the world already — LiteLLM publishes a couple of thousand of them — and being stale
 * there costs a wrong report, not a broken call.
 *
 * A dated snapshot needs no entry of its own: `claude-sonnet-4-5-20250929` is answered by `claude-sonnet-4-5`.
 * Nothing else inherits, so a model that is genuinely new is unknown until somebody writes it down.
 *
 * An application adds or replaces whatever it wants, so a model that came out today works without a release of
 * Trantor:
 *
 * ```kotlin
 * services.addModelCatalog { catalog, _ ->
 *     catalog.add("anthropic/claude-6", like = "anthropic/claude-opus-5") { copy(maxOutputTokens = 256_000) }
 * }
 * ```
 *
 * **In code and not in the configuration**, on purpose. A capability is an effort level, a range or a feature, and
 * written as text none of them are checked until the call is made: a typo in a level, a name that is not a model,
 * a number where a range goes. In Kotlin the compiler answers all three while it is being written, and the
 * application recompiling its own code is not a release of Trantor.
 *
 * A model described as another one is resolved when it is read and not when it is written, so it can name one that
 * a provider has not registered yet. Order does not matter by design and not by luck, which is the same rule the
 * registry follows.
 */
class ModelCatalog {
    private val specs = LinkedHashMap<String, ModelSpec>()
    private val described = LinkedHashMap<String, Described>()
    private val defaults = mutableMapOf<String, String>()

    @Synchronized
    fun add(spec: ModelSpec) = apply {
        described.remove(spec.reference)
        specs[spec.reference] = spec
    }

    /** Several models of one family, which is the usual shape: they differ in price and not in what they take. */
    fun add(vararg references: String, capabilities: ModelCapabilities) = apply {
        references.forEach { add(specOf(it, capabilities)) }
    }

    /**
     * A model described as another one, with what changed. It is the shape a new model usually has — the same as
     * the last, with a bigger ceiling — and the reason the catalog stays short.
     */
    @Synchronized
    fun add(reference: String, like: String, change: ModelCapabilities.() -> ModelCapabilities = { this }) = apply {
        specs.remove(reference)
        described[reference] = Described(like, change)
    }

    /**
     * What a model of this provider is when nobody described it: the newest one the catalog knows, which is the
     * closest guess there is. Naming it rather than repeating its capabilities means moving one line when the
     * next generation arrives.
     */
    @Synchronized
    fun addDefault(provider: String, like: String) = apply { defaults[provider] = like }

    /** Null when nobody wrote this model down and its provider registered no default. */
    fun find(provider: String, modelId: String) = find("$provider/$modelId")

    @Synchronized
    fun find(reference: String): ModelSpec? = resolve(reference, mutableSetOf())

    @Synchronized
    fun all(provider: String? = null) = (specs.keys + described.keys)
        .mapNotNull { resolve(it, mutableSetOf(), standInForTheNewest = false) }
        .filter { provider == null || it.provider == provider }

    /**
     * [standInForTheNewest] is off while a `like` is being followed, so naming a model that does not exist is an
     * error and not the default quietly answering in its place. A typo has to be a typo.
     */
    private fun resolve(
        reference: String,
        seen: MutableSet<String>,
        standInForTheNewest: Boolean = true,
    ): ModelSpec? {
        exactly(reference, seen)?.let { return it }

        val modelId = reference.substringAfter("/")

        (specs.keys + described.keys).lastOrNull { isSnapshotOf(reference, it) }?.let {
            return exactly(it, seen)?.copy(modelId = modelId)
        }

        if (!standInForTheNewest) return null

        val default = defaults[reference.substringBefore("/")] ?: return null

        return exactly(default, seen)?.copy(modelId = modelId, isGuess = true)
    }

    private fun exactly(reference: String, seen: MutableSet<String>): ModelSpec? {
        specs[reference]?.let { return it }

        val description = described[reference] ?: return null

        if (!seen.add(reference)) {
            error("The catalog goes in circles describing $reference: ${seen.joinToString(" -> ")}")
        }

        val like = resolve(description.like, seen, standInForTheNewest = false)
            ?: error("$reference is described as ${description.like}, which is not in the catalog")

        return specOf(reference, description.change(like.capabilities))
    }

    private fun specOf(reference: String, capabilities: ModelCapabilities) = ModelSpec(
        provider = reference.substringBefore("/"),
        modelId = reference.substringAfter("/"),
        capabilities = capabilities,
    )

    /**
     * Whether one id is the same model as another, pinned to a release of it. A provider publishes a dated
     * snapshot of a model it already has (`claude-sonnet-4-5-20250929`, `gpt-4.1-mini-2025-04-14`) and a version
     * suffix on the platforms that carry one, and those are the same model with the same capabilities.
     *
     * Nothing else counts, on purpose. `gpt-4` is written before `gpt-4o` and `gpt-4.1` without being either of
     * them, and `claude-sonnet-4-9` would be a model of a generation nobody described yet. Letting a plain prefix
     * inherit would hand an unknown model the capabilities of an older one and silently drop what it does take.
     */
    private fun isSnapshotOf(reference: String, entry: String): Boolean {
        if (!reference.startsWith(entry) || reference.length == entry.length) return false

        val rest = reference.substring(entry.length)

        return rest.startsWith("@") || rest.startsWith(":") || rest == "-latest" || DATES.any { it.matches(rest) }
    }

    private data class Described(val like: String, val change: ModelCapabilities.() -> ModelCapabilities)

    companion object {
        /** The three ways a provider dates a snapshot: `-20250929`, `-2025-04-14` and the older `-0613`. */
        private val DATES = listOf(
            Regex("""^-\d{8}$"""),
            Regex("""^-\d{4}-\d{2}-\d{2}$"""),
            Regex("""^-\d{4}$"""),
        )
    }
}
