package dev.studyflow.core.designsystem.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Navigation-owned scopes that let feature content participate in a shared-element transition. */
public data class StudyFlowSharedElementScope(
    val sharedTransitionScope: SharedTransitionScope,
    val animatedVisibilityScope: AnimatedVisibilityScope,
)

/** Shares this element when [scope] is available and remains unchanged in standalone previews. */
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

/** Stable, collision-free keys shared by list and detail destinations. */
public object StudyFlowSharedElementKeys {
    /** The title shared between a material grid cell and its detail app bar. */
    public fun materialTitle(id: String): String = "material-title-$id"

    /** The image shared between a material grid cell and its full detail preview. */
    public fun materialPreview(id: String): String = "material-preview-$id"

    /** The title shared between a task list row and its detail app bar. */
    public fun taskTitle(id: String): String = "task-title-$id"
}
