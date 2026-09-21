package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.models.catalog.ModelCatalog

/**
 * The list price of each OpenAI model, in dollars per million tokens, as the Standard tier of the OpenAI pricing
 * page had it on 2026-09-21 (`developers.openai.com/api/docs/pricing`).
 *
 * The price is the one for a short context. GPT-6 Astra, the GPT-5.6 family, GPT-5.5 and GPT-5.4 charge more past
 * a long prompt (272K tokens on the last two), and for an estimate the short price is the one most calls pay.
 *
 * Only the newest models charge a cache write. On the rest a write is billed as plain input, which is what a
 * missing cacheWrite means.
 *
 * Two old snapshots kept their launch price and have one of their own; every other snapshot costs what its family
 * costs.
 */
internal fun ModelCatalog.addOpenAIPrices() = apply {
    price("openai/gpt-6-astra", input = "10", output = "50", cacheRead = "1", cacheWrite = "12.50")

    price("openai/gpt-5.6-sol", input = "4", output = "20", cacheRead = "0.40", cacheWrite = "5")
    price("openai/gpt-5.6-terra", input = "2", output = "12", cacheRead = "0.20", cacheWrite = "2.50")
    price("openai/gpt-5.6-luna", input = "0.20", output = "1.20", cacheRead = "0.02", cacheWrite = "0.25")

    price("openai/gpt-5.5", input = "5", output = "30", cacheRead = "0.50")
    price("openai/gpt-5.4", input = "2.50", output = "15", cacheRead = "0.25")

    price("openai/gpt-5", input = "1.25", output = "10", cacheRead = "0.125")
    price("openai/gpt-5-mini", input = "0.25", output = "2", cacheRead = "0.025")
    price("openai/gpt-5-nano", input = "0.05", output = "0.40", cacheRead = "0.005")

    price("openai/o1", input = "15", output = "60", cacheRead = "7.50")
    price("openai/o3", input = "2", output = "8", cacheRead = "0.50")
    price("openai/o3-mini", input = "1.10", output = "4.40", cacheRead = "0.55")
    price("openai/o4-mini", input = "1.10", output = "4.40", cacheRead = "0.275")

    price("openai/gpt-4.1", input = "2", output = "8", cacheRead = "0.50")
    price("openai/gpt-4.1-mini", input = "0.40", output = "1.60", cacheRead = "0.10")
    price("openai/gpt-4.1-nano", input = "0.10", output = "0.40", cacheRead = "0.025")

    price("openai/gpt-4o", input = "2.50", output = "10", cacheRead = "1.25")
    price("openai/gpt-4o-2024-05-13", input = "5", output = "15")
    price("openai/gpt-4o-mini", input = "0.15", output = "0.60", cacheRead = "0.075")

    // gpt-4 is listed by its snapshot, gpt-4-0613, and gpt-4-turbo by gpt-4-turbo-2024-04-09
    price("openai/gpt-4", input = "30", output = "60")
    price("openai/gpt-4-turbo", input = "10", output = "30")
    price("openai/gpt-3.5-turbo", input = "0.50", output = "1.50")
    price("openai/gpt-3.5-turbo-1106", input = "1", output = "2")
}
