package dev.studyflow.core.testing.coroutines

import dev.studyflow.core.common.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * A [DispatcherProvider] whose three dispatchers all share one virtual clock.
 *
 * Production code picks a dispatcher for a reason — CPU work off the main thread, disk work off
 * both — and a test should not have to care which one. Backing all three with the same
 * [TestDispatcher] means `runTest` controls every coroutine the code under test launches, whichever
 * dispatcher it asked for, so waiting is skipped rather than slept through and a test never needs a
 * timeout to be "long enough".
 *
 * @param dispatcher the dispatcher to hand out. A [StandardTestDispatcher] queues work instead of
 *   running it eagerly, which keeps ordering bugs visible instead of accidentally hiding them.
 */
public class TestDispatcherProvider(
    public val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : DispatcherProvider {
    override val default: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val main: CoroutineDispatcher get() = dispatcher
}

/** Builds a [TestDispatcherProvider] on the scheduler of this scope, as created by `runTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
public fun TestScope.testDispatcherProvider(): TestDispatcherProvider =
    TestDispatcherProvider(StandardTestDispatcher(testScheduler))

/**
 * Replaces [Dispatchers.Main] for the duration of a test.
 *
 * Anything holding a `viewModelScope` dispatches to the main dispatcher, which does not exist in a
 * plain JVM test; without this the test fails with "Module with the Main dispatcher had failed to
 * initialize". Swapping in a [TestDispatcher] is the standard fix, and doing it in an extension
 * rather than in a `@BeforeEach` per test class means nobody forgets the matching
 * [Dispatchers.resetMain], which would otherwise leak a dead dispatcher into the next test sharing
 * the JVM.
 *
 * ```kotlin
 * @ExtendWith(MainDispatcherExtension::class)
 * class TimerViewModelTest { ... }
 * ```
 *
 * Register it with `@RegisterExtension` instead when the test needs the [dispatcher] itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class MainDispatcherExtension(
    public val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : BeforeEachCallback,
    AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        Dispatchers.setMain(dispatcher)
    }

    override fun afterEach(context: ExtensionContext) {
        Dispatchers.resetMain()
    }
}
