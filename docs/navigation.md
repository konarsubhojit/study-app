# Navigation

StudyFlow uses Navigation 3. `AppRoute` is the only destination model: every key implements
`NavKey`, is serializable, and is stored directly in the `rememberNavBackStack` owned by the app.
`NavDisplay` supplies predictive-back progress and the app uses the design-system transitions for
forward, pop, and predictive-pop animations. It is hosted in a `SharedTransitionLayout`, so feature
content can add shared elements without replacing the navigation host.

`rememberNavBackStack` restores the stack after process death. The saveable-state entry decorator
also scopes `rememberSaveable` screen state to each back-stack entry. Verify both behaviors by
enabling **Developer options > Don't keep activities**, entering a note on a destination, leaving
the app, and returning to it.

## Deep links

External entry points must use `StudyFlowDeepLinks.uriFor` rather than constructing URI strings.
This keeps notification and widget targets aligned with the typed destination model.

| Source or destination | URI |
| --- | --- |
| Home | `studyflow://home` |
| Timer | `studyflow://timer` |
| Running timer | `studyflow://timer/running` |
| Materials | `studyflow://materials` |
| Completed upload | `studyflow://materials/{materialId}` |
| Tasks | `studyflow://tasks` |
| Reminder notification | `studyflow://tasks/{taskId}` |
| Settings | `studyflow://settings` |
| Widget action | `studyflow://widget/{home|timer|materials|tasks|settings}` |

Unknown or malformed links are rejected rather than opening a partially parsed destination.

## Navigation Compose 2 fallback

If a required dependency becomes incompatible with Navigation 3, retain `AppRoute` and
`StudyFlowDeepLinks` as the public navigation contract. Replace only the app host with Navigation
Compose 2's typed `NavHost`, using each serializable route object directly with typed
`composable<T>` and `navigate(route)` APIs. Map the same deep-link results to route objects and use
the same design-system transitions. Do not introduce string routes or concatenate route arguments.
Return to Navigation 3 once the dependency constraint is removed; feature call sites and external
URIs will not need to change.
