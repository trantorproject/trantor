package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.schemas.StrictRules

/**
 * What OpenAI holds a model to, as "Supported schemas" of its Structured Outputs lists it (read on 2026-09-29). The
 * length of a string is not there: it only names `minLength` and `maxLength` among what fine-tuned models do not
 * take either.
 */
internal object OpenAIStrictRules: StrictRules(
    keywords = setOf(
        "pattern",
        "format",
        "minimum",
        "maximum",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "multipleOf",
        "minItems",
        "maxItems",
    ),
    formats = setOf("date-time", "time", "date", "duration", "email", "hostname", "ipv4", "ipv6", "uuid"),
    takesRecursion = true,
)
