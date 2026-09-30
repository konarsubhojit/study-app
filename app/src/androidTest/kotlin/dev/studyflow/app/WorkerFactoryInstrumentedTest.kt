package dev.studyflow.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import dalvik.system.DexFile
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Every worker in the installed APK can be constructed by the [WorkerFactory] WorkManager actually
 * uses.
 *
 * Guards the release-only symptom where every `@HiltWorker` logged
 * `WM-WorkerFactory: Could not instantiate … NoSuchMethodException … <init> [Context, WorkerParameters]`
 * and sync, digests and reminder integrity checks silently never ran: `HiltWorkerFactory` had no
 * binding for any worker (the `androidx.hilt:hilt-compiler` processor was not applied), so WorkManager
 * fell back to a reflective constructor no `@AssistedInject` worker has. Nothing crashes when that
 * happens, which is why it is asserted here rather than left to the logcat scan in
 * [ProductionReleaseSmokeInstrumentedTest].
 *
 * The factory is read from the live [WorkManager] singleton, not resolved from the Hilt graph
 * directly, so the same assertion also fails if something calls `WorkManager.getInstance` before
 * `StudyFlowApplication`'s `Configuration.Provider` can supply the Hilt factory — that locks in a
 * default configuration for the life of the process.
 *
 * Workers are discovered by scanning the APK's own dex files rather than listed by hand, so a
 * worker added later is covered without touching this test. Run as
 * `pixel6Api34ProductionReleaseTestAndroidTest`, the scan and the construction both see the
 * R8-shrunk output (see "Minified release smoke test" in CONTRIBUTING.md).
 */
class WorkerFactoryInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyWorkerInTheApkIsConstructedByTheConfiguredWorkerFactory() {
        val workers = appWorkerClasses()
        // A scan that silently found nothing would pass vacuously; the worker from the original
        // bug report pins that it actually walked the app's code.
        assertTrue(
            "the dex scan found no StudyFlow workers — it is not reading the app APK: $workers",
            workers.any { it.name == SYNC_WORKER },
        )

        val factory = WorkManager.getInstance(context).configuration.workerFactory
        val failures =
            workers.mapNotNull { worker ->
                runCatching {
                    // `build()` goes through `createWorkerWithDefaultFallback`, the same path
                    // WorkManager's own `WorkerWrapper` takes, and throws when neither the factory
                    // nor the reflective fallback can produce an instance of this class.
                    TestListenableWorkerBuilder.from(context, worker).setWorkerFactory(factory).build()
                }.exceptionOrNull()?.let { "${worker.name}: $it" }
            }

        assertTrue(
            "${factory.javaClass.name} could not construct ${failures.size} of ${workers.size} " +
                "workers:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    /** Every concrete [ListenableWorker] in the app's own namespace, read from the installed APK. */
    @Suppress("DEPRECATION") // DexFile is the only public way to list the classes an APK contains.
    private fun appWorkerClasses(): List<Class<out ListenableWorker>> {
        val dex = DexFile(context.packageCodePath)
        return try {
            dex
                .entries()
                .asSequence()
                .filter { it.startsWith(APP_NAMESPACE) }
                .map { Class.forName(it, false, context.classLoader) }
                .filter { ListenableWorker::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) }
                .map { it.asSubclass(ListenableWorker::class.java) }
                .sortedBy { it.name }
                .toList()
        } finally {
            dex.close()
        }
    }

    private companion object {
        const val APP_NAMESPACE = "dev.studyflow."
        const val SYNC_WORKER = "dev.studyflow.core.scheduling.SyncWorker"
    }
}
