package dev.botta.trantor.hosting

interface HostedService {
    val name: String get() = javaClass.simpleName

    fun start()
    fun stop(timeoutSeconds: Int = 30)
}
