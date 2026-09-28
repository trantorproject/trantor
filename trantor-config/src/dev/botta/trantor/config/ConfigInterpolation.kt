package dev.botta.trantor.config

/**
 * References inside a value to other keys of the same configuration, resolved when the value is read:
 *
 * ```json
 * { "db": { "host": "${DB_HOST:localhost}", "url": "jdbc:postgresql://${db.host}/app" },
 *   "github": { "authorization": "Bearer ${GITHUB_TOKEN}" } }
 * ```
 *
 * - `${path}` is the value of that key, whatever its case. A variable of the environment is a key under its own
 *   name, so `${GITHUB_TOKEN}` reads it: that is how a secret stays out of a file that is checked in.
 * - `${path:default}` is the default when the key is not there or is null. It is taken as it is, from the first
 *   colon on, so a default can have colons of its own; it has no references.
 * - A key that is not there and has no default is empty.
 * - The value of a reference is resolved too, and a chain of them that comes back to where it started fails with
 *   [ConfigInterpolationError] instead of going round forever.
 * - `$${` is a `${` of the text, and a `${` without its `}` is left as it is.
 *
 * Being resolved when read, a reference takes what the providers say at that moment: a later provider, like the
 * environment, overrides a key and every value that refers to it.
 */
internal object ConfigInterpolation {
    private const val OPEN = "\${"
    private const val ESCAPED = "$\${"

    /** [value] with its references resolved, each with [valueOf] the key it names. */
    fun resolve(value: String, valueOf: (String) -> String?): String {
        if (!value.contains(OPEN)) return value

        val resolved = StringBuilder()
        var at = 0

        while (at < value.length) {
            when {
                value.startsWith(ESCAPED, at) -> {
                    resolved.append(OPEN)
                    at += ESCAPED.length
                }
                value.startsWith(OPEN, at) -> {
                    val end = value.indexOf('}', at + OPEN.length)
                    if (end == -1) return resolved.append(value, at, value.length).toString()

                    val reference = value.substring(at + OPEN.length, end)
                    val key = reference.substringBefore(':').trim()
                    val default = if (':' in reference) reference.substringAfter(':') else null

                    resolved.append(valueOf(key) ?: default.orEmpty())
                    at = end + 1
                }
                else -> resolved.append(value[at++])
            }
        }

        return resolved.toString()
    }
}
