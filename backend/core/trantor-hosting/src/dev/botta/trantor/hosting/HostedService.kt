package dev.botta.trantor.hosting

interface HostedService {
    fun start()
    fun stop(timeoutSeconds: Int = 30)
}
