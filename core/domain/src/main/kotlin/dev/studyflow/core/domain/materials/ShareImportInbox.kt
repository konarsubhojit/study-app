package dev.studyflow.core.domain.materials

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries URIs from a share intent (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) from the activity that
 * received them to whichever screen is ready to import them (issue #37).
 *
 * An activity can receive a share intent before the materials screen — or its view model — exists
 * yet, on both a cold start and a warm [android.app.Activity.onNewIntent]. Routing the URIs through
 * an app-scoped singleton rather than a constructor argument or a navigation route means the
 * activity never has to know whether anything is listening yet, and a late subscriber still sees
 * whatever arrived first.
 *
 * [consume] clears the pending batch once import has started so recomposition, process
 * restoration, or another subscriber joining later cannot trigger the same import twice; the
 * content-hash check in [MaterialImporter] would keep a repeat harmless in any case, but not
 * reimporting is cheaper than importing and discarding.
 */
@Singleton
public class ShareImportInbox
    @Inject
    constructor() {
        private val mutablePending = MutableStateFlow<List<String>>(emptyList())

        /** URIs waiting to be imported, most recent share sheet only. */
        public val pending: StateFlow<List<String>> = mutablePending.asStateFlow()

        /** Records a newly received batch of share-intent URIs for the next subscriber to import. */
        public fun offer(uris: List<String>) {
            if (uris.isEmpty()) return
            mutablePending.value = uris
        }

        /** Clears the pending batch once it has been handed off for import. */
        public fun consume() {
            mutablePending.value = emptyList()
        }
    }
