package dev.botta.trantor.serviceProvider

typealias ServiceConfiguration<T> = (instance: T, services: ServiceProvider) -> Unit
