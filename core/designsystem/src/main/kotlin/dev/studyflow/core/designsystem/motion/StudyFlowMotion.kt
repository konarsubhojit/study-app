package dev.studyflow.core.designsystem.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/**
 * Motion tokens (issue #17).
 *
 * Animation is the part of a design system features are most likely to reinvent, because every
 * `tween(300)` looks harmless on its own. These are the only durations and easing curves the app
 * uses; `docs/design-system.md` explains which to reach for.
 *
 * The transitions are deliberately short and fade-led. Predictive back hands the system a
 * user-driven progress value, so a screen transition must look correct at every point between 0 and
 * 1 and must not depend on running to completion — a slide that travels the full width of the
 * screen cannot do that, a fade with a small scale can.
 */
public object StudyFlowMotion {
    /** Durations, in milliseconds. */
    public object Durations {
        /** 100ms — a state change on a single control, such as a selection. */
        public const val SHORT: Int = 100

        /** 200ms — a small component entering or leaving. */
        public const val MEDIUM: Int = 200

        /** 300ms — a whole screen or pane transition. */
        public const val LONG: Int = 300
    }

    /** The Material 3 easing set; anything else is a bug. */
    public object Easings {
        /** Movement that starts and ends on screen. */
        public val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** Content entering the screen. */
        public val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** Content leaving the screen. */
        public val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }

    /** Spec for anything that moves or resizes. */
    public fun <T> spatial(durationMillis: Int = Durations.LONG): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = Easings.Standard)

    /** Spec for anything that only changes colour or alpha. */
    public fun <T> effects(durationMillis: Int = Durations.MEDIUM): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = Easings.Standard)

    /** Destination arriving, forwards. */
    public val enter: EnterTransition =
        fadeIn(animationSpec = arriving()) + scaleIn(initialScale = GROW_FROM, animationSpec = arriving())

    /** Destination leaving, forwards. */
    public val exit: ExitTransition = fadeOut(animationSpec = leaving())

    /** Destination arriving on back, including a predictive-back gesture the user may abandon. */
    public val popEnter: EnterTransition = fadeIn(animationSpec = arriving())

    /** Destination leaving on back; scales down so the gesture reads as "putting this away". */
    public val popExit: ExitTransition =
        fadeOut(animationSpec = leaving()) + scaleOut(targetScale = SHRINK_TO, animationSpec = leaving())

    private fun <T> arriving(): FiniteAnimationSpec<T> =
        tween(durationMillis = Durations.LONG, easing = Easings.EmphasizedDecelerate)

    private fun <T> leaving(): FiniteAnimationSpec<T> =
        tween(durationMillis = Durations.MEDIUM, easing = Easings.EmphasizedAccelerate)

    private const val GROW_FROM = 0.95f
    private const val SHRINK_TO = 0.95f
}
