package dev.studyflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.studyflow.app.navigation.AppRoute
import dev.studyflow.app.navigation.StudyFlowApp
import dev.studyflow.app.navigation.StudyFlowDeepLinks

internal class MainActivity : ComponentActivity() {
    private var deepLinkHandler: (AppRoute) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialRoute =
            intent.data
                ?.takeIf { savedInstanceState == null }
                ?.let(StudyFlowDeepLinks::routeFor)

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
        StudyFlowDeepLinks.routeFor(intent.data)?.let(deepLinkHandler)
    }
}
