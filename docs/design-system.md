# Design system

Everything visual lives in `:core:designsystem`. A feature applies the theme, reads tokens, and
writes no colour, dimension, font size or animation of its own. The decision and its trade-offs are
in [ADR 0007](adr/0007-design-system.md).

## Theming

```kotlin
StudyFlowTheme {
    StudyFlowScaffold(topBar = { /* … */ }) { padding ->
        Column(modifier = Modifier.padding(padding)) { /* … */ }
    }
}
```

`StudyFlowTheme` follows the system light/dark setting and uses wallpaper colours where the platform
has them (API 31+), falling back to the StudyFlow brand palette everywhere else. Pass `darkTheme` or
`dynamicColor` explicitly to honour a user preference; both end up in `ColorSchemeChoice`, which is
the only place the fallback rule is written down.

## Tokens

| Token | Read it as | Never write |
|---|---|---|
| Colour | `MaterialTheme.colorScheme.primary` | `Color(0xFF…)` |
| Type | `MaterialTheme.typography.bodyLarge` | `fontSize = 14.sp` |
| Shape | `MaterialTheme.shapes.medium` | `RoundedCornerShape(12.dp)` |
| Spacing | `MaterialTheme.spacing.medium` | `padding(16.dp)` |
| Motion | `StudyFlowMotion.spatial()` | `tween(300)` |

The spacing scale is `extraSmall` (4) · `small` (8) · `medium` (16) · `large` (24) ·
`extraLarge` (32) · `huge` (48), all in dp and all on a 4.dp grid. Type sizes are declared in `sp`,
so the whole scale grows with the system font setting; nothing is pinned to a dp height.

## Edge-to-edge and insets

The app draws behind the system bars. `StudyFlowScaffold` pads its content by
`WindowInsets.safeDrawing` — system bars, display cutout and the IME — and passes the padding to the
content lambda exactly as `Scaffold` does. Widen or narrow those insets when a screen genuinely
needs it (a full-bleed image, a list that should scroll under the status bar), but consume them
somewhere: content that is unreadable behind the keyboard or the gesture handle is a bug.

## Adaptive layouts

Layout decisions come from the width of the *window*, so split screen and folding work for free:

```kotlin
StudyFlowListDetail(
    hasSelection = selected != null,
    listPane = { MaterialList(onSelect = …) },
    detailPane = { MaterialDetail(selected) },
)
```

`WindowWidthClass` classifies the window as `Compact` (< 600.dp), `Medium` (< 840.dp) or `Expanded`,
and `ListDetailStrategy` turns that plus the selection into one pane or two. Both are pure functions
— assert against them in unit tests rather than inflating a window.

## Motion

Use the tokens in `StudyFlowMotion`; do not invent a duration or a curve.

| Use | Token |
|---|---|
| A control changing state | `Durations.SHORT` (100ms) |
| A component entering or leaving | `Durations.MEDIUM` (200ms) |
| A screen or pane transition | `Durations.LONG` (300ms) |
| Anything that moves or resizes | `spatial()` |
| Anything that only changes colour or alpha | `effects()` |
| Navigation | `enter` / `exit` / `popEnter` / `popExit` |

Transitions are fade-led with a small scale, and never travel the full width of the screen. That is
a predictive-back requirement rather than a taste: the system drives the transition from the user's
gesture, so every frame between 0 and 1 has to look deliberate — including the frames of a gesture
that is abandoned halfway and reversed.

Motion is also an accessibility setting. Respect the platform's reduced-motion preference rather
than animating unconditionally, and never make an animation the only signal that something changed.

## Screenshot tests

`:core:designsystem` and `:core:ui` apply the `studyflow.screenshot` convention plugin, which renders the
`@Preview` functions under `src/screenshotTest` with layoutlib and compares them against the images
checked in under `src/screenshotTestDebug/reference`.

```bash
./gradlew :core:designsystem:validateDebugScreenshotTest   # compare; also runs as part of `check`
./gradlew :core:designsystem:updateDebugScreenshotTest     # re-record after an intended change
./gradlew :core:ui:updateDebugScreenshotTest               # re-record shared component references
```

Re-record only when the change to the images is the change you meant to make, and say so in the pull
request. The covered variants are light, dark, dynamic light, dynamic dark, 200% font scale, and the
list/detail layout at compact, medium and expanded widths.
