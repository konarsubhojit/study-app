package dev.studyflow.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner shape tokens (issue #17).
 *
 * Five steps, matching the Material 3 shape scale, so a component picks a token rather than
 * inventing a corner radius: cards and sheets that agree on their corners are what makes a set of
 * screens look like one app.
 */
public val StudyFlowShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
