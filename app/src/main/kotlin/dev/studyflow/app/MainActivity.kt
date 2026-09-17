package dev.studyflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.studyflow.app.navigation.AppRoute
import dev.studyflow.app.navigation.MaterialsRoute
import dev.studyflow.app.navigation.StudyFlowApp
import dev.studyflow.app.navigation.StudyFlowDeepLinks
import dev.studyflow.app.share.ShareIntentUris
import dev.studyflow.core.domain.materials.ShareImportInbox
import javax.inject.Inject

@AndroidEntryPoint
internal class MainActivity : ComponentActivity() {
    @Inject
    lateinit var shareImportInbox: ShareImportInbox

    private var deepLinkHandler: (AppRoute) -> Unit = {}
    private var handledDeepLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledDeepLink = savedInstanceState?.getString(HANDLED_DEEP_LINK)
        val incomingDeepLink = intent.data
        val deepLinkRoute = StudyFlowDeepLinks.routeForNewIntent(incomingDeepLink, handledDeepLink)
        if (deepLinkRoute != null) handledDeepLink = incomingDeepLink.toString()

        // A share intent has no `AppRoute` of its own to resolve; offering it to the inbox now
        // means the materials screen sees it as soon as it exists, whether that is this launch or a
        // navigation the user makes moments later.
        val sharedUris = ShareIntentUris.extract(intent)
        if (sharedUris.isNotEmpty()) shareImportInbox.offer(sharedUris.map { it.toString() })

        setContent {
            StudyFlowApp(
                initialRoute = deepLinkRoute ?: MaterialsRoute().takeIf { sharedUris.isNotEmpty() },
                registerDeepLinkHandler = { deepLinkHandler = it },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        StudyFlowDeepLinks.routeFor(intent.data)?.let { route ->
            handledDeepLink = intent.dataString
            deepLinkHandler(route)
        }

        val sharedUris = ShareIntentUris.extract(intent)
        if (sharedUris.isNotEmpty()) {
            shareImportInbox.offer(sharedUris.map { it.toString() })
            deepLinkHandler(MaterialsRoute())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        handledDeepLink?.let { outState.putString(HANDLED_DEEP_LINK, it) }
        super.onSaveInstanceState(outState)
    }

    private companion object {
        private const val HANDLED_DEEP_LINK = "handled-deep-link"
    }
}
