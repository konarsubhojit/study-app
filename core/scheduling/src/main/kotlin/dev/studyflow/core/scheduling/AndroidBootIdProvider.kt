package dev.studyflow.core.scheduling

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.studyflow.core.common.time.BootIdProvider
import dev.studyflow.core.model.BootId
import javax.inject.Inject

/**
 * Real [BootIdProvider], backed by `Settings.Global.BOOT_COUNT`.
 *
 * `BOOT_COUNT` needs no permission and increments exactly once per boot, which is the only
 * guarantee [BootId] documents — see [ReminderActionExecutor.currentAnchor] for the inline
 * equivalent this mirrors.
 */
public class AndroidBootIdProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BootIdProvider {
        override fun current(): BootId =
            BootId(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString())
    }
