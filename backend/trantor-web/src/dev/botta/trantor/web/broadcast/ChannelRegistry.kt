package dev.botta.trantor.web.broadcast

import dev.botta.trantor.core.broadcast.Channel

class ChannelRegistry {
    private val compiledChannels = mutableListOf<CompiledChannel>()
    private val PARAM_REGEX = Regex("\\{([a-zA-Z0-9_]+)}")

    fun add(channel: Channel) {
        compiledChannels.add(compile(channel))
    }

    private fun compile(channel: Channel): CompiledChannel {
        val paramNames = mutableListOf<String>()

        val pattern = PARAM_REGEX.replace(channel.path) { match ->
            val name = match.groupValues[1]
            paramNames += name
            "([a-zA-Z0-9_]+)"
        }

        val regex = Regex("^$pattern$")

        return CompiledChannel(channel, regex, paramNames)
    }

    fun match(path: String): ChannelMatch? {
        for (compiled in compiledChannels) {
            val match = compiled.regex.matchEntire(path) ?: continue

            val params = compiled.paramNames
                .mapIndexed { index, name -> name to match.groupValues[index + 1] }
                .toMap()

            return ChannelMatch(compiled.channel, params)
        }

        return null
    }
}

data class CompiledChannel(val channel: Channel, val regex: Regex, val paramNames: List<String>)

data class ChannelMatch(val channel: Channel, val params: Map<String, String>)
