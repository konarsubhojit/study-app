package dev.studyflow.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dev.studyflow.core.datastore.proto.SyncMode
import dev.studyflow.core.domain.materials.UploadWaitReason
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/** The device conditions an upload's WorkManager constraints are evaluated against. */
public data class UploadConditions(
    val connected: Boolean,
    val metered: Boolean,
    val batteryLow: Boolean,
)

/**
 * Why a queued upload is not running, mirroring exactly the constraints
 * [WorkManagerMaterialUploadCoordinator] enqueues with: a network (unmetered when Wi-Fi-only) and a
 * battery that is not low. `null` when nothing is queued or every constraint is met.
 */
internal fun uploadWaitReason(
    hasQueuedUploads: Boolean,
    syncMode: SyncMode,
    conditions: UploadConditions,
): UploadWaitReason? =
    when {
        !hasQueuedUploads -> null
        !conditions.connected -> UploadWaitReason.WAITING_FOR_NETWORK
        syncMode == SyncMode.SYNC_MODE_WIFI_ONLY && conditions.metered -> UploadWaitReason.WAITING_FOR_WIFI
        conditions.batteryLow -> UploadWaitReason.WAITING_FOR_BATTERY
        else -> null
    }

/**
 * Live [UploadConditions] from the platform: the default network's capabilities and the battery's
 * low/okay broadcasts. Only observed while the catalogue is on screen.
 */
internal fun Context.uploadConditions(): Flow<UploadConditions> =
    combine(networkConditions(), batteryLow()) { network, batteryLow ->
        UploadConditions(connected = network.first, metered = network.second, batteryLow = batteryLow)
    }.distinctUntilChanged()

/** `(connected, metered)` for the default network. */
private fun Context.networkConditions(): Flow<Pair<Boolean, Boolean>> =
    callbackFlow {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    trySend(connected to metered)
                }

                override fun onLost(network: Network) {
                    trySend(false to false)
                }
            }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.onStart { emit(currentNetworkConditions()) }

private fun Context.currentNetworkConditions(): Pair<Boolean, Boolean> {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return false to false
    val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    return connected to connectivity.isActiveNetworkMetered
}

/**
 * Whether the battery is low in WorkManager's sense. The platform's own low/okay broadcasts are
 * what `setRequiresBatteryNotLow` listens to, so the same signal is used here.
 */
private fun Context.batteryLow(): Flow<Boolean> =
    callbackFlow {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_BATTERY_LOW -> trySend(true)
                        Intent.ACTION_BATTERY_OKAY -> trySend(false)
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            }
        // Protected system broadcasts; no other app can send them, but the receiver still need not
        // be exported to receive them.
        ContextCompat.registerReceiver(
            this@batteryLow,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { unregisterReceiver(receiver) }
    }.onStart { emit(currentBatteryLow()) }

private fun Context.currentBatteryLow(): Boolean {
    val battery = getSystemService(BatteryManager::class.java) ?: return false
    if (battery.isCharging) return false
    val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return percent in 0..LOW_BATTERY_PERCENT
}

/** The platform's default `config_lowBatteryWarningLevel`. */
private const val LOW_BATTERY_PERCENT = 15
