package dev.studyflow.core.designsystem.layout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The scaffold every StudyFlow screen is built on (issue #17).
 *
 * The app draws edge to edge, so content that ignores the insets ends up under the status bar, the
 * gesture handle or the keyboard. `safeDrawing` is the union of all three — system bars, display
 * cutout and IME — which makes "obscured content" a bug that has to be introduced deliberately
 * rather than the default a screen has to remember to fix.
 *
 * @param contentWindowInsets insets the content is padded by; widen or narrow it, do not drop it.
 * @param content receives the padding to apply, exactly as `Scaffold` does.
 */
@Composable
public fun StudyFlowScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}
