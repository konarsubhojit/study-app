package dev.studyflow.core.datastore.lifecycle

import dev.studyflow.core.datastore.ActiveTimerStore
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.domain.lifecycle.DataEraser
import dev.studyflow.core.network.auth.TokenStore
import javax.inject.Inject

/**
 * The preference-shaped half of an account deletion (issue #78).
 *
 * Three erasers rather than one because they fail independently and for different reasons — the
 * settings file is credential-encrypted, the timer anchor is device-protected and readable before
 * first unlock, and the tokens live in a Keystore-backed store that can throw on its own — and a
 * user who is told "settings could not be cleared" can act on that, where "preferences failed"
 * tells them nothing.
 */
public class SettingsEraser
    @Inject
    constructor(
        private val settings: UserSettingsStore,
    ) : DataEraser {
        override val name: String = "settings"

        override suspend fun erase() {
            settings.clear()
        }
    }

/**
 * Erases the active-timer anchor.
 *
 * This is the store that decides whether the app believes a stopwatch is running, so it is erased
 * for account deletion and excluded from backup for the same reason: a timer must never outlive
 * the device it was started on.
 */
public class ActiveTimerEraser
    @Inject
    constructor(
        private val activeTimer: ActiveTimerStore,
    ) : DataEraser {
        override val name: String = "active timer"

        override suspend fun erase() {
            activeTimer.clear()
        }
    }

/** Erases the stored credentials, which is what signs the device out for good. */
public class TokenEraser
    @Inject
    constructor(
        private val tokens: TokenStore,
    ) : DataEraser {
        override val name: String = "credentials"

        override suspend fun erase() {
            tokens.clear()
        }
    }
