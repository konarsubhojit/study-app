package dev.studyflow.core.scheduling

import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.datastore.proto.SyncMode
import dev.studyflow.core.domain.materials.UploadNetworkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [UploadNetworkSettings] over the persisted sync mode, re-applying the new constraint to uploads
 * already queued so the change takes effect immediately rather than only for later imports.
 */
public class WorkManagerUploadNetworkSettings(
    private val settingsStore: UserSettingsStore,
    private val coordinator: WorkManagerMaterialUploadCoordinator,
) : UploadNetworkSettings {
    override val wifiOnly: Flow<Boolean> =
        settingsStore.data.map { it.syncMode == SyncMode.SYNC_MODE_WIFI_ONLY }.distinctUntilChanged()

    override suspend fun setWifiOnly(wifiOnly: Boolean) {
        settingsStore.update {
            syncMode = if (wifiOnly) SyncMode.SYNC_MODE_WIFI_ONLY else SyncMode.SYNC_MODE_ANY_NETWORK
        }
        coordinator.reapplyQueuedConstraints()
    }
}
