package dev.studyflow.core.datastore

import com.google.protobuf.CodedOutputStream
import dev.studyflow.core.datastore.proto.SyncMode
import dev.studyflow.core.datastore.proto.Theme
import dev.studyflow.core.datastore.proto.UserSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class UserSettingsSchemaTest {
    @Test
    fun `new settings have documented defaults`() {
        val settings = UserSettings.getDefaultInstance()

        assertEquals(Theme.THEME_SYSTEM, settings.theme)
        assertEquals(25, settings.defaultFocusMinutes)
        assertEquals(5, settings.defaultBreakMinutes)
        assertEquals(false, settings.remindersEnabled)
        assertEquals(9, settings.reminderHour)
        assertEquals(0, settings.reminderMinute)
        assertEquals(1_073_741_824L, settings.storageQuotaBytes)
        assertEquals(SyncMode.SYNC_MODE_WIFI_ONLY, settings.syncMode)
        assertEquals(false, settings.digestEnabled)
        assertEquals(480, settings.maximumSessionMinutes)
        assertEquals(120, settings.inactivityPromptMinutes)
    }

    @Test
    fun `a future unknown field does not prevent an older schema from reading settings`() {
        val known = UserSettings.newBuilder().setDefaultFocusMinutes(50).build()
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    output.write(known.toByteArray())
                    CodedOutputStream.newInstance(output).also {
                        it.writeString(99, "future-value")
                        it.flush()
                    }
                }.toByteArray()

        val parsed = UserSettings.parseFrom(bytes)

        assertEquals(50, parsed.defaultFocusMinutes)
        assertEquals(Theme.THEME_SYSTEM, parsed.theme)
    }
}
