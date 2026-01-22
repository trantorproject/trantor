package dev.botta.trantor.hosting

interface HostLifetime {
    fun onStarted(action: () -> Unit)

    fun onStopping(action: () -> Unit)

    fun onStopped(action: () -> Unit)

    fun stopApplication()
}
