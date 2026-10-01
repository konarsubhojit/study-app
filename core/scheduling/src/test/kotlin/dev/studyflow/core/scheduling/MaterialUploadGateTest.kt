package dev.studyflow.core.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("MaterialUploadGate")
class MaterialUploadGateTest {
    @Test
    fun `releasing every queued upload at once still transfers at most the cap concurrently`() =
        runTest {
            val gate = MaterialUploadGate(maxConcurrentUploads = 2)
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val release = CompletableDeferred<Unit>()

            val uploads =
                List(6) {
                    async {
                        gate.withPermit {
                            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                            release.await()
                            active.decrementAndGet()
                        }
                    }
                }
            testScheduler.advanceUntilIdle()
            assertEquals(2, peak.get(), "only two uploads may be inside the gate while the rest wait")

            release.complete(Unit)
            uploads.awaitAll()
            assertEquals(2, peak.get())
            assertEquals(0, active.get())
        }
}
