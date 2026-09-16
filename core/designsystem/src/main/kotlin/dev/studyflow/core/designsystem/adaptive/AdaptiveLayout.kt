package dev.studyflow.core.designsystem.adaptive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.studyflow.core.designsystem.motion.StudyFlowMotion
import dev.studyflow.core.designsystem.theme.spacing

/** The width class of the window the caller is composed in, recomputed when the window resizes. */
@Composable
public fun rememberWindowWidthClass(): WindowWidthClass {
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    return remember(density, widthPx) {
        WindowWidthClass.fromWidthDp(with(density) { widthPx.toDp().value.toInt() })
    }
}

/** Width of the list pane when both panes are shown; the detail pane takes the rest. */
private val ListPaneWidth = 360.dp

/**
 * A list/detail screen that adapts to the window (issue #17).
 *
 * Which panes are shown is [ListDetailStrategy]'s decision, so this composable only renders it.
 * Single-pane changes are animated with the shared motion tokens rather than a transition invented
 * per feature, and the fade-led transition stays correct at every point of a predictive-back
 * gesture the user may still abandon.
 *
 * @param hasSelection whether an item is selected; drives the single-pane choice.
 * @param listPane the list of items.
 * @param detailPane the selected item, or a placeholder when [hasSelection] is false.
 */
@Composable
public fun StudyFlowListDetail(
    hasSelection: Boolean,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WindowWidthClass = rememberWindowWidthClass(),
) {
    val strategy = ListDetailStrategy.of(widthClass, hasSelection)

    AnimatedContent(
        targetState = strategy,
        modifier = modifier.fillMaxSize(),
        transitionSpec = { StudyFlowMotion.enter togetherWith StudyFlowMotion.exit },
        label = "list-detail",
    ) { target ->
        when (target) {
            ListDetailStrategy.ListOnly -> listPane()
            ListDetailStrategy.DetailOnly -> detailPane()
            ListDetailStrategy.ListAndDetail -> TwoPane(listPane = listPane, detailPane = detailPane)
        }
    }
}

@Composable
private fun TwoPane(
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Box(modifier = Modifier.width(ListPaneWidth)) { listPane() }
        VerticalDivider()
        Box(modifier = Modifier.fillMaxSize()) { detailPane() }
    }
}
