package dev.studyflow.core.designsystem.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import dev.studyflow.core.designsystem.adaptive.WindowWidthClass
import dev.studyflow.core.designsystem.adaptive.rememberWindowWidthClass
import dev.studyflow.core.designsystem.layout.StudyFlowScaffold

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

/**
 * The app's top-level navigation surface (issue #167).
 *
 * Material 3 specifies a navigation bar for three to five destinations and a navigation rail once
 * the window is wide enough, so which surface is drawn follows the window width class — never the
 * device type, per ADR 0015. Labels are kept to a single line: a bar that wraps "Material s" onto
 * two lines is the clearest signal an app is unfinished, and at 200% font scale an unbounded label
 * wraps on any phone.
 *
 * @param items at most five destinations; more than that belongs behind another entry point.
 * @param selected the selected destination key, or `null` when the current screen is not a
 *   top-level destination and nothing should read as selected.
 */
@Composable
public fun <T> StudyFlowNavigationSuite(
    items: List<StudyFlowNavigationItem<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WindowWidthClass = rememberWindowWidthClass(),
    content: @Composable () -> Unit,
) {
    require(items.size <= MAX_DESTINATIONS) {
        "A navigation bar holds at most $MAX_DESTINATIONS destinations, not ${items.size}"
    }

    if (widthClass == WindowWidthClass.Compact) {
        StudyFlowScaffold(
            modifier = modifier,
            bottomBar = {
                NavigationBar {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = item.key == selected,
                            onClick = { onSelect(item.key) },
                            icon = { NavigationItemIcon(item = item, selected = selected) },
                            label = { NavigationItemLabel(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) { content() }
        }
    } else {
        StudyFlowScaffold(modifier = modifier) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                NavigationRail {
                    items.forEach { item ->
                        NavigationRailItem(
                            selected = item.key == selected,
                            onClick = { onSelect(item.key) },
                            icon = { NavigationItemIcon(item = item, selected = selected) },
                            label = { NavigationItemLabel(item.label) },
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

@Composable
private fun <T> NavigationItemIcon(
    item: StudyFlowNavigationItem<T>,
    selected: T?,
) {
    Icon(
        imageVector = if (item.key == selected) item.selectedIcon else item.icon,
        // The label beside the icon already names the destination, so describing it again only
        // makes a screen reader announce everything twice.
        contentDescription = null,
    )
}

@Composable
private fun NavigationItemLabel(label: String) {
    Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The Material 3 maximum for a navigation bar. */
private const val MAX_DESTINATIONS = 5
