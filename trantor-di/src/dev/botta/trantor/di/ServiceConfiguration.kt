package dev.botta.trantor.di

typealias ServiceConfiguration<T> = (instance: T, services: ServiceProvider) -> Unit
