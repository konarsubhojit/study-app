# 7. One design system: tokens, dynamic colour with a brand fallback, edge-to-edge, adaptive panes

- Status: accepted
- Date: 2026-09-16

## Context

Every screen StudyFlow will grow — timer, materials, tasks, insights, settings — is written by a
different vertical slice of work, and each of them needs colours, spacing, corner radii, text
styles, window insets and transitions. Left to themselves, features converge on
"whatever looked right that afternoon": a literal `16.dp` here and `14.dp` there, a hand-rolled
`tween(300)`, and a screen that looks fine on a phone and wastes half a tablet.

Three platform behaviours make this worse than a purely cosmetic problem. Dynamic colour only
exists from API 31, but `minSdk` is 26, so a theme that assumes wallpaper colours has no palette at
all on older devices. From Android 15 apps are edge-to-edge whether they asked for it or not, so a
screen that ignores insets puts content under the status bar or the keyboard. And predictive back
hands the app a user-driven progress value, so a transition that only looks right when it runs to
completion looks broken while the gesture is in flight.

## Decision

`:core:designsystem` owns the whole visual language, and nothing else is allowed to define one.

- **Tokens, not literals.** Colour, typography, shape and spacing are declared once. Material 3
  supplies the first three; spacing has no Material equivalent, so the module adds a 4.dp scale
  behind `LocalSpacing` — the one CompositionLocal the detekt allowlist permits.
- **Dynamic colour is a request, not an assumption.** `ColorSchemeChoice` resolves
  (dark, dynamic requested, dynamic supported) to one of four schemes, so the brand palette is a
  real fallback rather than dead code, and the branch is a pure function that unit tests can pin.
- **Edge-to-edge is the default.** `StudyFlowTheme` makes the window edge-to-edge and matches the
  system bar icon contrast to the scheme; `StudyFlowScaffold` pads content by `safeDrawing`, the
  union of system bars, display cutout and IME. Ignoring insets has to be deliberate.
- **Layout follows the window, never the device.** `WindowWidthClass` classifies the window at the
  Material breakpoints, and `ListDetailStrategy` turns that plus "is something selected" into the
  pane layout shared by materials and tasks. Split screen and folding are then free.
- **Motion is a token set.** `StudyFlowMotion` owns the durations, easings and screen transitions.
  They are fade-led and short, which is what keeps them correct at every point of a predictive-back
  gesture the user may abandon.
- **Screenshots are the gate.** The AGP Compose preview screenshot plugin (`studyflow.screenshot`)
  renders light, dark, dynamic and 200% font scale variants plus the phone and tablet pane layouts
  on the JVM, and `check` fails when they change.

## Consequences

- A feature's build file applies a convention plugin and its screens read `MaterialTheme.*` and
  `MaterialTheme.spacing`; a hard-coded colour, dimension or font size outside this module is a
  review failure with a specific place to move it to.
- Theme regressions — a swapped colour role, a palette that no longer falls back, text that clips at
  200% font scale — are caught by a diff of a checked-in PNG rather than by someone remembering to
  toggle dark mode on a device.
- Reference images are binary files in the repository. They are only recorded deliberately, with
  `./gradlew :core:designsystem:updateDebugScreenshotTest`, and only modules that gain something
  from them apply the plugin.
- The screenshot plugin is still an alpha behind `android.experimental.enableScreenshotTest`, and it
  renders with layoutlib rather than on a device: it proves layout and colour, not behaviour.
- Dynamic colour cannot be asserted against a real wallpaper by a JVM test. The unit tests cover the
  decision, the screenshots cover the rendering; the wallpaper itself remains a manual check.
