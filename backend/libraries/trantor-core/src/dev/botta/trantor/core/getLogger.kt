package dev.botta.trantor.core

import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun getLogger(name: String): Logger = LoggerFactory.getLogger(name)

fun getLogger(clazz: Class<*>): Logger = getLogger(clazz.name)

fun Any.getLogger(): Logger = getLogger(this.javaClass)

inline fun <reified T> getLogger() = getLogger(T::class.java)
