package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.schemas.StrictRules

/**
 * What Anthropic holds a model to, as "JSON Schema limitations" of its structured outputs lists it (read on
 * 2026-09-29), which strict tools share: no bound on a number or on the length of a string, a list that asks for no
 * more than one item, and no schema that refers to itself.
 */
internal object AnthropicStrictRules: StrictRules(
    keywords = setOf("pattern", "format", "minItems", "allOf", "default"),
    formats = setOf("date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid"),
    largestMinItems = 1,
    takesRecursion = false,
)
