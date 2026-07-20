package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.data.remote.mapper.MessageMapper
import dev.blazelight.p4oc.data.server.ActiveServerApiProvider
import dev.blazelight.p4oc.data.workspace.WorkspaceClient
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import dev.blazelight.p4oc.domain.server.ServerGeneration
import dev.blazelight.p4oc.domain.server.WorkspaceKey
import dev.blazelight.p4oc.domain.workspace.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SessionRepositoryProvider(
    private val activeServerApiProvider: ActiveServerApiProvider,
    private val messageMapper: MessageMapper,
    private val connectionManager: ConnectionManager,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    data class Lease(
        val workspaceClient: WorkspaceClient,
        val repository: SessionRepositoryImpl,
    )

    private data class Key(
        val serverKey: String,
        val generation: Long,
        val workspaceKey: String,
    )

    private data class Entry(
        val workspaceClient: WorkspaceClient,
        val repository: SessionRepositoryImpl,
        val eventJob: Job,
        var refCount: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val entries = mutableMapOf<Key, Entry>()

    private companion object {
        const val TAG = "SessionRepositoryProvider"
    }

    init {
        // Deliver SSE reconnects to every live workspace repository so open conversations
        // self-heal without navigation (issue #14). The synthetic OpenCodeEvent.Connected never
        // reaches the per-workspace fan-out (it carries no directory), so we broadcast it here on
        // each non-Connected → Connected transition of the shared connection.
        scope.launch {
            var wasConnected = false
            connectionManager.connectionState.collect { state ->
                val nowConnected = state is ConnectionState.Connected
                if (nowConnected && !wasConnected) {
                    broadcastReconnect()
                }
                wasConnected = nowConnected
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // resilience guard: one bad repo must not stop the broadcast
    private fun broadcastReconnect() {
        // Generation is a single global counter in ConnectionManager, so one generation == one
        // connection == one server: filtering by generation alone correctly targets exactly the
        // repositories on the reconnected connection, without depending on server-URL normalization.
        val generation = connectionManager.currentGeneration ?: return
        val repositories = synchronized(this) {
            entries.filterKeys { it.generation == generation.value }
                .map { it.value.repository }
        }
        repositories.forEach { repository ->
            try {
                repository.acceptEvent(OpenCodeEvent.Connected)
            } catch (e: Exception) {
                AppLog.e(TAG, "Reconnect broadcast failed for a workspace: ${e.message}", e)
            }
        }
    }

    fun acquire(workspace: Workspace, generation: ServerGeneration): Lease = synchronized(this) {
        val key = workspace.toProviderKey(generation)
        val entry = entries.getOrPut(key) {
            val workspaceClient = WorkspaceClient(workspace, generation, activeServerApiProvider)
            val repository = SessionRepositoryImpl(workspaceClient, messageMapper)
            Entry(
                workspaceClient = workspaceClient,
                repository = repository,
                eventJob = collectWorkspaceEvents(workspace, generation, repository),
                refCount = 0,
            )
        }
        entry.refCount += 1
        Lease(entry.workspaceClient, entry.repository)
    }

    fun release(workspace: Workspace, generation: ServerGeneration) {
        val repositoryToClose = synchronized(this) {
            val key = workspace.toProviderKey(generation)
            val entry = entries[key] ?: return
            entry.refCount -= 1
            if (entry.refCount > 0) return
            entries.remove(key)
            entry
        }
        repositoryToClose.eventJob.cancel()
        repositoryToClose.repository.close()
    }

    @Suppress("TooGenericExceptionCaught") // resilience guard: one bad event must not kill the collector
    private fun collectWorkspaceEvents(
        workspace: Workspace,
        generation: ServerGeneration,
        repository: SessionRepositoryImpl,
    ): Job = scope.launch {
        connectionManager.scopedEvents.collect { scopedEvent ->
            if (scopedEvent.serverRef == workspace.server &&
                scopedEvent.generation == generation &&
                scopedEvent.workspaceKey == workspace.key
            ) {
                try {
                    repository.acceptEvent(scopedEvent.event)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // A single malformed/unexpected event must never permanently kill this
                    // workspace's live event delivery (issue #14: chat froze until re-entry).
                    AppLog.e(
                        TAG,
                        "Dropping event ${scopedEvent.event::class.simpleName} for ${workspace.key}: ${e.message}",
                        e,
                    )
                }
            }
        }
    }

    private fun Workspace.toProviderKey(generation: ServerGeneration): Key = Key(
        serverKey = server.endpointKey,
        generation = generation.value,
        workspaceKey = key.stableKey(),
    )

    private fun WorkspaceKey.stableKey(): String = when (this) {
        WorkspaceKey.Global -> "global"
        is WorkspaceKey.Directory -> "directory:$value"
        is WorkspaceKey.SessionScoped -> "session:${sessionId.value}"
    }
}
