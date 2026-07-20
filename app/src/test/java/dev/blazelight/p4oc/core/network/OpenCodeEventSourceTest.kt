package dev.blazelight.p4oc.core.network

import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.data.remote.mapper.EventMapper
import dev.blazelight.p4oc.data.remote.mapper.MessageMapper
import dev.blazelight.p4oc.domain.model.OpenCodeEvent
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeEventSourceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mockkObject(AppLog)
        every { AppLog.d(any(), any<String>()) } returns Unit
        every { AppLog.d(any(), any<() -> String>()) } returns Unit
        every { AppLog.v(any(), any<String>()) } returns Unit
        every { AppLog.v(any(), any<() -> String>()) } returns Unit
        every { AppLog.w(any(), any<String>()) } returns Unit
        every { AppLog.w(any(), any<String>(), any()) } returns Unit
        every { AppLog.e(any(), any<String>()) } returns Unit
        every { AppLog.e(any(), any<String>(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(AppLog)
    }

    @Test
    fun `isStale flags a silent Connected socket past the threshold`() {
        val source = newSource()
        try {
            val now = 1_000_000L
            val staleFrame = now - 61_000L // > SSE_STALE_MS (60s)
            val freshFrame = now - 30_000L

            // Connected + no frame for > 60s => stale (the zombie-socket case, issue #14).
            assertEquals(true, source.isStale(now, staleFrame, ConnectionState.Connected))
            // Connected but a recent frame => not stale.
            assertEquals(false, source.isStale(now, freshFrame, ConnectionState.Connected))
            // No frame yet (0L) => never stale, even if "Connected".
            assertEquals(false, source.isStale(now, 0L, ConnectionState.Connected))
            // Not Connected => watchdog leaves reconnect to the normal error/escalation path.
            assertEquals(false, source.isStale(now, staleFrame, ConnectionState.Disconnected))
            assertEquals(false, source.isStale(now, staleFrame, ConnectionState.Connecting))
        } finally {
            source.shutdown()
        }
    }

    private fun newSource(): OpenCodeEventSource = OpenCodeEventSource(
        okHttpClient = OkHttpClient(),
        json = json,
        baseUrl = "http://127.0.0.1:1",
        eventMapper = EventMapper(json, MessageMapper(json)),
    )

    @Test
    fun `slow collector receives more than previous delta buffer capacity without loss`() = runTest {
        val source = OpenCodeEventSource(
            okHttpClient = OkHttpClient(),
            json = json,
            baseUrl = "http://127.0.0.1:1",
            eventMapper = EventMapper(json, MessageMapper(json)),
        )
        val collected = mutableListOf<String>()
        var collector: Job? = null

        try {
            collector = launch {
                source.events.collect { event ->
                    val delta = (event as? OpenCodeEvent.MessagePartUpdated)?.delta ?: return@collect
                    delay(1)
                    collected += delta
                }
            }
            yield()
            val emit = source.javaClass.getDeclaredMethod(
                "parseAndEmitEvent",
                String::class.java,
                Long::class.javaPrimitiveType
            )
                .apply { isAccessible = true }
            val generation = source.javaClass.getDeclaredField("generation")
                .apply { isAccessible = true }
            generation.setLong(source, 1L)

            repeat(EVENT_COUNT) { index ->
                emit.invoke(source, globalPartDeltaJson(delta = index.toString()), 1L)
            }

            withTimeout(5_000) {
                while (collected.size < EVENT_COUNT) delay(10)
            }
        } finally {
            collector?.cancel()
            source.shutdown()
        }

        assertEquals((0 until EVENT_COUNT).map { it.toString() }, collected)
    }

    private fun globalPartDeltaJson(delta: String): String =
        """
        {
          "directory": "/workspace",
          "payload": {
            "type": "message.part.updated",
            "properties": {
              "part": {
                "id": "part-1",
                "sessionID": "session-1",
                "messageID": "message-1",
                "type": "text",
                "text": "ignored"
              },
              "delta": "$delta"
            }
          }
        }
        """.trimIndent()

    private companion object {
        const val EVENT_COUNT = 300
    }
}
