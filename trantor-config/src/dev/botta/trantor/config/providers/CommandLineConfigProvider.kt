package dev.botta.trantor.config.providers

import dev.botta.trantor.config.ConfigManager

/**
 * Configuration taken from the arguments the application was started with, as .NET reads them:
 *
 * - `--httpServer.port=9000` and `httpServer.port=9000` set `httpServer.port`;
 * - `--env staging` takes the next argument as the value;
 * - `--verbose`, with nothing after it or followed by another `--`, is `true`.
 *
 * An argument with a single dash, or with no dashes and no `=`, is left alone, so the application can still have
 * commands and short options of its own (`migrate`, `-v`). Added last, it overrides every other provider.
 */
class CommandLineConfigProvider(private val args: Array<String>): ConfigProviderBase() {
    override fun load() {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            i++
            val isSwitch = arg.startsWith(SWITCH)
            if (!isSwitch && (arg.startsWith('-') || !arg.contains('='))) continue

            val keyAndValue = arg.removePrefix(SWITCH)
            if (keyAndValue.contains('=')) {
                set(keyAndValue.substringBefore('='), keyAndValue.substringAfter('='))
            } else if (i < args.size && !args[i].startsWith(SWITCH)) {
                set(keyAndValue, args[i])
                i++
            } else {
                set(keyAndValue, "true")
            }
        }
    }

    private companion object {
        const val SWITCH = "--"
    }
}

fun ConfigManager.addCommandLine(args: Array<String>) = apply {
    add(CommandLineConfigProvider(args))
}
