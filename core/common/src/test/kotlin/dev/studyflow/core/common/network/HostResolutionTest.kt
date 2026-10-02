package dev.studyflow.core.common.network

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

class HostResolutionTest {
    @Test
    fun `DNS and unresolved addresses are recognized directly or wrapped`() {
        listOf(UnknownHostException("private"), UnresolvedAddressException()).forEach {
            assertTrue(it.isHostResolutionFailure())
            assertTrue(IOException("wrapper", it).isHostResolutionFailure())
        }
    }

    @Test
    fun `ordinary transport errors and cyclic causes are not DNS failures`() {
        assertFalse(IOException("reset").isHostResolutionFailure())
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertFalse(first.isHostResolutionFailure())
    }
}
