package dev.botta.trantor.di

/**
 * What `ServiceRegistry.configure` runs on an instance once it exists.
 *
 * It gets the [ServiceProvider] too, so a configuration can pull in whatever else it needs.
 */
typealias ServiceConfiguration<T> = (instance: T, services: ServiceProvider) -> Unit
