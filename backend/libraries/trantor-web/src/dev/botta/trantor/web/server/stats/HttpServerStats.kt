package dev.botta.trantor.web.server.stats

import org.eclipse.jetty.util.thread.QueuedThreadPool

data class HttpServerStats(
    val statsGatheringStartMs: Long,
    val requests: RequestStats,
    val dispatches: DispatchStats,
    val responses: ResponsesStats,
    val threads: ThreadsStats,
    val managementThreads: ThreadsStats?,
)

data class RequestStats(
    val totalRequests: Int,
    val activeRequests: Int,
    val maxActiveRequests: Int,
    val totalRequestsTimeMs: Long,
    val meanRequestTimeMs: Double,
    val maxRequestTimeMs: Long,
    val standardDeviationRequestTimeMs: Double,
)

data class DispatchStats(
    val totalDispatched: Int,
    val activeDispatched: Int,
    val maxActiveDispatched: Int,
    val totalDispatchedTimeMs: Long,
    val meanDispatchedTimeMs: Double,
    val maxDispatchedTimeMs: Long,
    val standardDeviationDispatchedTimeMs: Double,
    val totalRequestsSuspended: Int,
    val totalRequestsExpired: Int,
    val totalRequestsResumed: Int
)

data class ResponsesStats(
    val responses1xx: Int,
    val responses2xx: Int,
    val responses3xx: Int,
    val responses4xx: Int,
    val responses5xx: Int,
    val bytesSentTotal: Long
)

data class ThreadsStats(
    val state: String,
    val isLowOnThreads: Boolean,
    val minThreads: Int,
    val threads: Int,
    val maxThreads: Int,
    val idleThreads: Int,
    val reservedThreads: Int,
    val busyThreads: Int,
    val queueSize: Int
)
//
//fun StatisticsHandler.getStats(threadPool: QueuedThreadPool, managementThreadPool: QueuedThreadPool?): HttpServerStats {
//    return HttpServerStats(
//        statsGatheringStartMs = this.statsOnMs,
//        requests = RequestStats(
//            totalRequests = this.requests,
//            activeRequests = this.requestsActive,
//            maxActiveRequests = this.requestsActiveMax,
//            totalRequestsTimeMs = this.requestTimeTotal,
//            meanRequestTimeMs = this.requestTimeMean,
//            maxRequestTimeMs = this.requestTimeMax,
//            standardDeviationRequestTimeMs = this.requestTimeStdDev,
//        ),
//        dispatches = DispatchStats(
//            totalDispatched = this.dispatched,
//            activeDispatched = this.dispatchedActive,
//            maxActiveDispatched = this.dispatchedActiveMax,
//            totalDispatchedTimeMs = this.dispatchedTimeTotal,
//            meanDispatchedTimeMs = this.dispatchedTimeMean,
//            maxDispatchedTimeMs = this.dispatchedTimeMax,
//            standardDeviationDispatchedTimeMs = this.dispatchedTimeStdDev,
//            totalRequestsSuspended = this.asyncRequests,
//            totalRequestsExpired = this.expires,
//            totalRequestsResumed = this.asyncDispatches,
//        ),
//        responses = ResponsesStats(
//            responses1xx = this.responses1xx,
//            responses2xx = this.responses2xx,
//            responses3xx = this.responses3xx,
//            responses4xx = this.responses4xx,
//            responses5xx = this.responses5xx,
//            bytesSentTotal = this.responsesBytesTotal,
//        ),
//        threads = threadsStats(threadPool),
//        managementThreads = if (managementThreadPool != null) threadsStats(managementThreadPool) else null
//    )
//}

private fun threadsStats(threadPool: QueuedThreadPool) = ThreadsStats(
    state = threadPool.state,
    isLowOnThreads = threadPool.isLowOnThreads,
    minThreads = threadPool.minThreads,
    threads = threadPool.threads,
    maxThreads = threadPool.maxThreads,
    idleThreads = threadPool.idleThreads,
    reservedThreads = threadPool.reservedThreads,
    busyThreads = threadPool.busyThreads,
    queueSize = threadPool.queueSize
)
