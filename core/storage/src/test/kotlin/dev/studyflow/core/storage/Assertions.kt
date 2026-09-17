package dev.studyflow.core.storage

import org.junit.jupiter.api.Assertions.assertTrue

/**
 * `assertThrows` for suspending calls.
 *
 * JUnit's version takes a non-inline lambda, which cannot contain a suspension point; this one is
 * inline, so the call under test stays inside the coroutine `runTest` started.
 */
internal inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    val failure = runCatching(block).exceptionOrNull()
    assertTrue(failure is T) { "expected ${T::class.simpleName} but got $failure" }
    return failure as T
}
