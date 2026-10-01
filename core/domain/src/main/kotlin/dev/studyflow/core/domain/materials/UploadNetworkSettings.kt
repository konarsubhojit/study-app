package dev.studyflow.core.domain.materials

import kotlinx.coroutines.flow.Flow

/**
 * Whether material uploads wait for an unmetered (Wi-Fi) network.
 *
 * A port rather than a raw settings field because changing it has to reach uploads that are already
 * queued: WorkManager fixes a request's constraints when it is enqueued, so without re-applying them
 * a user who allows mobile data would still see every queued upload waiting for Wi-Fi.
 */
public interface UploadNetworkSettings {
    public val wifiOnly: Flow<Boolean>

    public suspend fun setWifiOnly(wifiOnly: Boolean)
}
