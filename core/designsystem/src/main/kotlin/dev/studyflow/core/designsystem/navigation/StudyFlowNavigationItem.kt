package dev.studyflow.core.designsystem.navigation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One top-level destination of [StudyFlowNavigationSuite].
 *
 * The selected destination is drawn with [selectedIcon] and the rest with [icon], so selection is
 * carried by the icon itself as well as by the indicator and the colour — three signals, none of
 * which is colour alone.
 *
 * @param key whatever the caller navigates by; compared with `==` to decide what is selected.
 */
@Immutable
public data class StudyFlowNavigationItem<out T>(
    val key: T,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)
