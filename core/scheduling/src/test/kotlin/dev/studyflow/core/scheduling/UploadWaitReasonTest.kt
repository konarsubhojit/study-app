package dev.studyflow.core.scheduling

import dev.studyflow.core.datastore.proto.SyncMode
import dev.studyflow.core.domain.materials.UploadWaitReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("uploadWaitReason")
class UploadWaitReasonTest {
    private val mobileData = UploadConditions(connected = true, metered = true, batteryLow = false)
    private val wifi = UploadConditions(connected = true, metered = false, batteryLow = false)

    @Test
    fun `a queued upload on mobile data with Wi-Fi-only sync is waiting for Wi-Fi`() {
        assertEquals(
            UploadWaitReason.WAITING_FOR_WIFI,
            uploadWaitReason(hasQueuedUploads = true, SyncMode.SYNC_MODE_WIFI_ONLY, mobileData),
        )
    }

    @Test
    fun `mobile data is fine when uploads may use any network`() {
        assertNull(uploadWaitReason(hasQueuedUploads = true, SyncMode.SYNC_MODE_ANY_NETWORK, mobileData))
    }

    @Test
    fun `no connection at all reads as waiting for a network, not for Wi-Fi`() {
        assertEquals(
            UploadWaitReason.WAITING_FOR_NETWORK,
            uploadWaitReason(true, SyncMode.SYNC_MODE_WIFI_ONLY, wifi.copy(connected = false)),
        )
    }

    @Test
    fun `a low battery holds uploads even on Wi-Fi`() {
        assertEquals(
            UploadWaitReason.WAITING_FOR_BATTERY,
            uploadWaitReason(true, SyncMode.SYNC_MODE_WIFI_ONLY, wifi.copy(batteryLow = true)),
        )
    }

    @Test
    fun `nothing queued means nothing is waiting`() {
        assertNull(uploadWaitReason(hasQueuedUploads = false, SyncMode.SYNC_MODE_WIFI_ONLY, mobileData))
        assertNull(uploadWaitReason(hasQueuedUploads = true, SyncMode.SYNC_MODE_WIFI_ONLY, wifi))
    }
}
