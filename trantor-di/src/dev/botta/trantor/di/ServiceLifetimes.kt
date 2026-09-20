package dev.botta.trantor.di

/** How long a resolved instance lives, and therefore how often it is built. */
enum class ServiceLifetimes {
    /** A new instance on every resolution. */
    Transient,

    /** One instance for the whole application, built the first time it is asked for. */
    Singleton,

    /** One instance per scope, on the thread that opened it. See `ServiceProvider.enterScope`. */
    Scoped,
}
