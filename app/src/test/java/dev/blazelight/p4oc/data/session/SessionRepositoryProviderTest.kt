package dev.blazelight.p4oc.data.session

import dev.blazelight.p4oc.core.network.ConnectionManager
import dev.blazelight.p4oc.core.network.ConnectionState
import dev.blazelight.p4oc.core.network.OpenCodeApi
import dev.blazelight.p4oc.data.remote.mapper.MessageMapper
import dev.blazelight.p4oc.data.server.ActiveServerApiProvider
import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import dev.blazelight.p4oc.domain.model.Session
import dev.blazelight.p4oc.domain.model.TokenUsage
import dev.blazelight.p4oc.domain.server.ScopedEvent
import dev.blazelight.p4oc.domain.server.ServerGeneration
import dev.blazelight.p4oc.domain.server.ServerRef
import dev.blazelight.p4oc.domain.session.SessionId
import dev.blazelight.p4oc.domain.workspace.Workspace
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryProviderTest {
    private val server = ServerRef.fromEndpointKey("http://fake.test")
    private val workspace = Workspace(server = server, directory = "/repo")
    private val generation = ServerGeneration(1)

    @Test
    fun `acquire reuses repository for same workspace generation`() {
        val provider = provider()

        val first = provider.acquire(workspace, generation)
        val second = provider.acquire(workspace, generation)

        assertSame(first.repository, second.repository)
        assertSame(first.workspaceClient, second.workspaceClient)
    }

    @Test
    fun `repository remains retained until final matching release`() {
        val provider = provider()
        val first = provider.acquire(workspace, generation)
        provider.acquire(workspace, generation)

        provider.release(workspace, generation)
        val afterSingleRelease = provider.acquire(workspace, generation)

        assertSame(first.repository, afterSingleRelease.repository)
    }

    @Test
    fun `final release closes repository and next acquire creates replacement`() {
        val provider = provider()
        val first = provider.acquire(workspace, generation)

        provider.release(workspace, generation)
        val second = provider.acquire(workspace, generation)

        assertNotSame(first.repository, second.repository)
        assertNotSame(first.workspaceClient, second.workspaceClient)
    }

    @Test
    fun `different generation gets separate repository`() {
        val provider = provider()

        val first = provider.acquire(workspace, generation)
        val second = provider.acquire(workspace, ServerGeneration(2))

        assertNotSame(first.repository, second.repository)
        assertNotSame(first.workspaceClient, second.workspaceClient)
    }

    @Test
    fun `provider routes scoped events to shared repository`() = runTest {
        val event = sessionCreatedEvent("s1")
        val provider = provider(
            scopedEvents = flowOf(
                ScopedEvent(
                    serverRef = server,
                    generation = generation,
                    workspaceKey = workspace.key,
                    event = event,
                ),
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        testScheduler.advanceUntilIdle()

        assertTrue(lease.repository.state.value is RepoState.Hydrating)
    }

    @Test
    fun `throwing event does not permanently kill workspace event collection`() = runTest {
        // First event throws inside acceptEvent; the collector must survive and process the next.
        val bad = mockk<Message> { every { sessionID } throws RuntimeException("boom") }
        val provider = provider(
            scopedEvents = flowOf(
                scoped(OpenCodeEvent.MessageUpdated(bad)),
                scoped(OpenCodeEvent.MessageUpdated(assistantMessage("m1"))),
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("m1"),
            lease.repository.messages(SessionId("s1")).value.map { it.message.id },
        )
    }

    @Test
    fun `reconnect refreshes messages for open sessions on every reconnect`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val provider = provider(
            api = api,
            connectionState = connectionState,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val lease = provider.acquire(workspace, generation)
        lease.repository.messages(SessionId("s1")) // register an actively-observed conversation
        testScheduler.advanceUntilIdle()

        // First reconnect: non-Connected -> Connected. This also runs the one-shot snapshot
        // hydration, which sets (and never clears) the repository's inFlight guard.
        connectionState.value = ConnectionState.Connected
        testScheduler.advanceUntilIdle()

        // Second reconnect. Advance on the intermediate Error so the collector observes the
        // non-Connected dwell (in production the server is down for seconds). The message refresh
        // must NOT be suppressed by the now-set inFlight guard — otherwise an open conversation
        // goes stale after the first reconnect (issue #14).
        connectionState.value = ConnectionState.Error("dropped")
        testScheduler.advanceUntilIdle()
        connectionState.value = ConnectionState.Connected
        testScheduler.advanceUntilIdle()

        // The open conversation self-heals via a REST message refetch on both reconnects.
        coVerify(atLeast = 2) { api.getMessages("s1", null, "/repo") }
    }

    private fun scoped(event: OpenCodeEvent): ScopedEvent = ScopedEvent(
        serverRef = server,
        generation = generation,
        workspaceKey = workspace.key,
        event = event,
    )

    private fun assistantMessage(id: String): Message.Assistant = Message.Assistant(
        id = id,
        sessionID = "s1",
        createdAt = 1L,
        parentID = "",
        providerID = "provider",
        modelID = "model",
        mode = "chat",
        agent = "assistant",
        cost = 0.0,
        tokens = TokenUsage(input = 0, output = 0),
    )

    private fun provider(
        scopedEvents: Flow<ScopedEvent> = emptyFlow(),
        connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected),
        api: OpenCodeApi = mockk<OpenCodeApi>(relaxed = true),
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
    ): SessionRepositoryProvider = SessionRepositoryProvider(
        activeServerApiProvider = ActiveServerApiProvider { _, _ -> api },
        messageMapper = MessageMapper(Json { ignoreUnknownKeys = true }),
        connectionManager = mockk<ConnectionManager> {
            every { this@mockk.scopedEvents } returns scopedEvents
            every { this@mockk.connectionState } returns connectionState
            every { this@mockk.currentGeneration } returns generation
        },
        dispatcher = dispatcher,
    )

    private fun sessionCreatedEvent(id: String): OpenCodeEvent.SessionCreated = OpenCodeEvent.SessionCreated(
        session = Session(
            id = id,
            projectID = "project-$id",
            directory = workspace.directory.orEmpty(),
            title = id,
            version = "1",
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )
}
