package dev.studyflow.core.designsystem.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

public data class StudyFlowSharedElementScope(
    val sharedTransitionScope: SharedTransitionScope,
    val animatedVisibilityScope: AnimatedVisibilityScope,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
public fun Modifier.studyFlowSharedElement(
    key: String,
    scope: StudyFlowSharedElementScope?,
): Modifier =
    if (scope == null) {
        this
    } else {
        with(scope.sharedTransitionScope) {
            sharedElement(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = scope.animatedVisibilityScope,
            )
        }
    }

public object StudyFlowSharedElementKeys {
    public fun materialTitle(id: String): String = "material-title-$id"

    public fun materialPreview(id: String): String = "material-preview-$id"

    public fun taskTitle(id: String): String = "task-title-$id"
}
