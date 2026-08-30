package me.egigoka.pomodorough.data.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointId
import computer.iroh.NoHandle

internal class IrohSyncTestEndpoint : Endpoint(NoHandle) {
    var shutdownCount = 0
        private set

    override fun isClosed() = shutdownCount > 0

    override suspend fun shutdown() {
        shutdownCount += 1
    }

    override fun id(): EndpointId = object : EndpointId(NoHandle) {
        override fun fmtShort() = "test-endpoint"
    }
}
