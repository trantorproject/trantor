# The model catalog and cost

Providers take parameters out between generations of the same family, and the one left behind is not ignored: it is
a 400. A reasoning model of OpenAI refuses `temperature`, and the Claude models from 4.7 on refuse a thinking budget.
So the same `ChatRequest` would work or fail depending on which model an alias points at. The catalog says what each
model takes, and the adapters send only that.

## What happens to a setting the model does not take

**The public api is wide and the adapter narrows it.** A setting the model would refuse is dropped with a
`ModelWarning` instead of being sent: nothing is lost in silence, and nothing breaks that a caller wrote reasonably.

`ChatSettings(failOnWarnings = true)` turns any of these into an error, which is what an application in
production usually wants.

With what the catalog says, an adapter does one of three things, and each says so:

| | example |
|---|---|
| **drops** what does not fit | `temperature` on Claude Opus 5 |
| **coerces** what is out of range | `temperature = 1.8` goes to Anthropic as `1.0`, since it stops at 1 and OpenAI goes to 2 |
| **translates** to the nearest value the model has | `Minimal` goes to Anthropic as `low` |

## Describing a model

**A new model is described as the one before it**, which is the shape it almost always has:

```kotlin
services.addModelCatalog { catalog, _ ->
    catalog.add("anthropic/claude-6", like = "anthropic/claude-opus-5", ModelPricing(input = "5", output = "25")) {
        copy(maxOutputTokens = 256_000)
    }
}
```

`like` means *"the same capabilities as that model, then apply this change"*. It is resolved **when the
catalog is read, not when the line runs**, so it can name a model whose provider has not registered yet
and the order of the calls does not matter — the same rule the registry follows. A `like` that names no
model is an error; the latest model below never answers in its place, so a typo stays a typo.

**A dated snapshot needs no entry.** `claude-sonnet-4-5-20250929` is answered by `claude-sonnet-4-5`, and
so are `@`, `:` and `-latest` suffixes. The three date shapes the providers use are `-20250929`,
`-2025-04-14` and the older `-0613`.

**Nothing else inherits**, deliberately. A loose prefix would make `gpt-4` answer for `gpt-4o` and
`gpt-4.1`, which are other models with other prices.

## A model nobody described

It **is taken to be the latest model of its provider**, which is what `setLatest` names:

```kotlin
setLatest("anthropic", "anthropic/claude-opus-5-5")
```

One slot per provider, so setting it again replaces it. When the next generation comes out, that line is what
moves.

A model that comes out is almost always the one before it with something taken away, so the newest entry
is the closest guess there is, and the call goes out working instead of failing on a parameter that
generation stopped taking.

A spec that came from there is marked `ModelSpec.isGuess`, and **every decision an adapter takes out of
one says so in its warning**:

> `claude-sonnet-9 does not take temperature, so it was not sent. That is what the newest model in the
> catalog takes; add claude-sonnet-9 to it if it takes more.`

That is the price, stated: a guess can drop something the model did accept, which a written entry cannot.
Saying it out loud is what keeps it from being silent. The same applies to an id that is not the
provider's model at all — an Anthropic-compatible server behind `anthropic/…` gets Claude's profile — and
one `catalog.add` fixes it.

## The one value invented out of nothing

Anthropic **requires** `max_tokens`, so there has to be a number even for a model with no ceiling known.
That is the only place a value is made up, and it comes with a warning naming the model and what to do:

> `claude-x is not in the model catalog, so the call asks for 4096 output tokens. Add it to the catalog,
> or set maxOutputTokens.`

Everywhere else the rule holds: **a value is only invented when the api forces it, and never in silence.**

## Reading it

```kotlin
val spec = services.get<ModelCatalog>().find("anthropic", "claude-opus-5")

if (ModelFeatures.StructuredOutput in spec!!.capabilities) { ... }
```

Each provider registers its own models, so `addAI()` is all an application needs.

## Cost

**It is an estimate and not the bill.** `CostCalculator` prices the tokens of a call with the list price of
its model, and that is all it knows. Batch and priority tiers, the higher price of a long context, where
the data is kept, discounts and taxes all move the bill away from it. It is close enough to see where the
money goes and to notice when it starts going faster, which is what it is for.

With `CostMiddleware` registered, every response carries it:

```kotlin
val estimate = response.info.estimatedCost

estimate?.cacheRead      // what the cache cost, next to what it saved on estimate.uncachedInput
estimate?.reasoning      // part of estimate.output
estimate?.total
```

A stream's parts go through untouched and its `response()` carries the estimate, since the tokens are
only known once the answer is over. Without the middleware, `CostCalculator(catalog).estimate(response)`
gives the same number.

A `CostEstimate` has the names of `Usage` without the `Tokens`: `input` is `uncachedInput` plus
`cacheRead` plus `cacheWrite`, `reasoning` is part of `output`, and `total` is input plus output. Estimates
of several calls add up with `+`, part by part. Nothing is rounded; a call costs fractions of a cent, and
rounding belongs to whoever shows the number.

A price is per million tokens, in dollars, on the line of the model it belongs to. Each provider brings
the prices of its models. An application prices a model it adds on the same line, and corrects a price,
or prices a model it never describes, with `price`:

```kotlin
services.addModelCatalog { catalog, _ ->
    catalog.price("openai/gpt-5.2", ModelPricing(input = "1.75", output = "14", cacheRead = "0.175"))
}
```

The numbers are text so they stay exactly what the price list says. A price is all an estimate needs,
so a model priced and never described in the catalog is estimated too. `inputPerMillion` is the plain price,
the one both providers call "input", and it is what the uncached input pays. A cache with no price of its
own is charged as plain input, which is what OpenAI does with a write. There is one price for writes,
although Anthropic charges more for a cache kept an hour: for an estimate, the five-minute price is close
enough.

**No number is better than a wrong one.** The estimate is null when:

- the catalog does not know the model, or knows it without a price;
- the model is standing in for the newest one. Guessing what a model takes keeps a call from failing;
  guessing what it costs gives a wrong number that looks right, off by however much cheaper the newest
  model is. Being off by a tier is an estimate, being off by a model is not;
- the usage does not say the input or the output, because half a bill passes for all of it.

A response is priced as `info.model`, the model that answered. That is usually a dated snapshot, and a
snapshot is priced as its family unless it has a price of its own, which a couple of old OpenAI snapshots
do. A model added `like` another takes what the other takes and not what it costs, so it has no price
until one is written for it. A model standing in for the newest one gets nothing of the newest one's
price, but keeps its own if somebody wrote it: what it takes is still a guess, what it costs is not.

The prices shipped with Trantor are the standard ones, read off the pricing pages of each provider. For
OpenAI that is the short-context price; GPT-6 Astra, GPT-6.1 Sol, GPT-5.6, GPT-5.5 and GPT-5.4 charge more past a
long prompt. A model the provider retired is not in the catalog at all: it answers 404, and describing it would only
make it look usable.
