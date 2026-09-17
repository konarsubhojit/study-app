package dev.studyflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.studyflow.app.navigation.AppRoute
import dev.studyflow.app.navigation.StudyFlowApp
import dev.studyflow.app.navigation.StudyFlowDeepLinks

@AndroidEntryPoint
internal class MainActivity : ComponentActivity() {
    private var deepLinkHandler: (AppRoute) -> Unit = {}
    private var handledDeepLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledDeepLink = savedInstanceState?.getString(HANDLED_DEEP_LINK)
        val incomingDeepLink = intent.data
        val initialRoute = StudyFlowDeepLinks.routeForNewIntent(incomingDeepLink, handledDeepLink)
        if (initialRoute != null) handledDeepLink = incomingDeepLink.toString()

        setContent {
            StudyFlowApp(
                initialRoute = initialRoute,
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        handledDeepLink?.let { outState.putString(HANDLED_DEEP_LINK, it) }
        super.onSaveInstanceState(outState)
    }

    private companion object {
        private const val HANDLED_DEEP_LINK = "handled-deep-link"
    }
}
