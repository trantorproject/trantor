package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.models.catalog.ModelCatalog

/**
 * The list price of each Claude, in dollars per million tokens, as the Anthropic pricing page had it on 2026-09-21
 * (`platform.claude.com/docs/en/about-claude/pricing`).
 *
 * The cache write is the five-minute one. A cache kept an hour costs more to write, and for an estimate the
 * difference is not worth a second price. Cache reads are a tenth of the input everywhere but on Fable 5.1 and
 * Mythos 5.1, where they are a fortieth.
 *
 * Claude 3 Haiku has no price: it is off the price list, and a price nobody publishes is not one to write here.
 */
internal fun ModelCatalog.addAnthropicPrices() = apply {
    price(
        "anthropic/claude-fable-5-1",
        "anthropic/claude-mythos-5-1",
        input = "10",
        output = "50",
        cacheRead = "0.25",
        cacheWrite = "12.50",
    )
    price(
        "anthropic/claude-fable-5",
        "anthropic/claude-mythos-5",
        input = "10",
        output = "50",
        cacheRead = "1",
        cacheWrite = "12.50",
    )

    price(
        "anthropic/claude-opus-5",
        "anthropic/claude-opus-4-8",
        "anthropic/claude-opus-4-7",
        "anthropic/claude-opus-4-6",
        "anthropic/claude-opus-4-5",
        input = "5",
        output = "25",
        cacheRead = "0.50",
        cacheWrite = "6.25",
    )
    price(
        "anthropic/claude-opus-4-1",
        "anthropic/claude-opus-4",
        input = "15",
        output = "75",
        cacheRead = "1.50",
        cacheWrite = "18.75",
    )

    price("anthropic/claude-sonnet-5", input = "2", output = "10", cacheRead = "0.20", cacheWrite = "2.50")
    price(
        "anthropic/claude-sonnet-4-6",
        "anthropic/claude-sonnet-4-5",
        "anthropic/claude-sonnet-4",
        input = "3",
        output = "15",
        cacheRead = "0.30",
        cacheWrite = "3.75",
    )

    price("anthropic/claude-haiku-4-5", input = "1", output = "5", cacheRead = "0.10", cacheWrite = "1.25")
}
