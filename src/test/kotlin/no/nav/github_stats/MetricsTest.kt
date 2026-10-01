package no.nav.github_stats

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.*

class MetricsTest {
    private val sleeps = mutableListOf<Long>()
    private val fakeSleep: (Long) -> Unit = { sleeps += it }

    @Test
    fun `completes immediately on success`() {
        var calls = 0
        retryOnIOException(3, 100, fakeSleep) { calls++ }
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `retries on IOException with exponential backoff and then succeeds`() {
        var calls = 0
        retryOnIOException(3, 100, fakeSleep) {
            calls++
            if (calls < 3) throw SocketTimeoutException("Read timed out")
        }
        assertEquals(3, calls)
        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test
    fun `rethrows last IOException when all attempts fail`() {
        var calls = 0
        assertFailsWith<IOException> {
            retryOnIOException(3, 100, fakeSleep) {
                calls++
                throw IOException("fail $calls")
            }
        }.also { assertEquals("fail 3", it.message) }
        assertEquals(3, calls)
        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test
    fun `does not retry non-IO exceptions`() {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            retryOnIOException(3, 100, fakeSleep) {
                calls++
                error("boom")
            }
        }
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `dummy address skips push`() {
        MetricsRegistry().push("dummy", "my-org")
    }
}
