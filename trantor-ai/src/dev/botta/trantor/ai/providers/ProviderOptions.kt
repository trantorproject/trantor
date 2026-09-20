package dev.botta.trantor.ai.providers

/**
 * Provider specific options of a request. Each adapter reads only its own and ignores the rest with a warning.
 */
class ProviderOptions private constructor(private val options: List<ProviderOption>) {
    val isEmpty get() = options.isEmpty()

    /** Which providers these options are for, so an adapter can warn about the ones that are not its own. */
    val providers get() = options.map { it.provider }.toSet()

    fun forProvider(provider: String) = options.filter { it.provider == provider }

    override fun toString() = options.toString()

    companion object {
        val None = ProviderOptions(emptyList())

        fun of(vararg options: ProviderOption) = ProviderOptions(options.toList())
    }
}

interface ProviderOption {
    val provider: String
}
