# Widgets and the Quick Settings tile

Three surfaces outside the app — a timer widget, a today's-tasks widget and a Quick Settings tile —
so that starting a session, or seeing what is due, never needs the app to be opened. All of them
live in `:app/src/main/kotlin/dev/studyflow/app/widget`, because a widget is an entry point into the
app: it needs the deep links and the Hilt graph a feature module is not allowed to depend on.

## What each surface does

| Surface | Reads | Acts |
|---|---|---|
| `TimerWidget` | `SessionRepository.activeState()` through `TimerEngine` | start / pause / resume, stop (medium and larger), tap opens the timer screen |
| `TodayTasksWidget` | `TaskRepository.observeOverdue()` + `observeToday()` | complete (medium and larger), snooze (large, and only when the task has a reminder), tap opens the task |
| `StudyTimerTileService` | the same timer state | one tap starts a session, one tap stops it |

Both widgets declare `SizeMode.Responsive`: a small cell shows the state and the primary control, a
medium cell adds the secondary action, and a large cell adds the detail line (the session note) or a
snooze button. The host picks the bucket; nothing measures itself.

## Updates are pushed, never polled

`updatePeriodMillis` is `0` in both `appwidget-provider` files, and a unit test asserts it — a
periodic update would wake the app on a device where nothing is happening, which is exactly what a
timer widget must not do.

Instead, `WidgetUpdater` is a `SessionCommandObserver`, bound `@IntoSet` in `WidgetModule` next to
the one the foreground service uses. Repositories notify that set *after* a command has been
committed durably, so a start, pause, resume or stop — whether it came from the app, the
notification or the tile — lands on the widgets and the tile within one commit. Task actions call
`WidgetUpdater.refresh()` directly for the same reason.

The one thing that genuinely changes every second is the elapsed time, and it is not the app that
draws it: a running timer widget renders a platform `Chronometer` through `AndroidRemoteViews`, with
its base derived from the event log. The launcher ticks it in its own process, so the seconds move
while the app does no work at all. This keeps the rule from
[ADR 0003](adr/0003-timer-event-sourcing.md) intact — elapsed time is still derived from the
`SessionEvent` log, never counted by a loop.

## Surviving reboot, updates and a dead process

Nothing is cached in the widget. Every rendering reads the repositories — Room and the DataStore
anchor — through `WidgetEntryPoint`, so the widget is correct whether the app process is alive or
was killed hours ago. After a reboot or an app update the platform broadcasts
`ACTION_APPWIDGET_UPDATE` to the registered receivers, which is why both receivers declare that
filter (also asserted by a test) and why no state has to be restored by hand. The tile re-renders in
`onStartListening`, and `StudyTimerTileService.requestListening` nudges it when state changes while
it is on screen.

Commands from a widget or the tile are written straight to `SessionRepository` rather than by
starting the foreground service: a background start can be refused by the system, while a database
write cannot. The service then follows from the same observer set.

## Theming

`StudyFlowGlanceTheme` wraps every widget. On Android 12 and later it uses Glance's default
providers, which are the wallpaper-derived `system_*` colours, so the widget follows dynamic colour
and the host's own light/dark widget theme. Below that the platform has no such resources and the
app palette from `:core:designsystem` (`StudyFlowColorSchemes`) stands in. Corners come from
`android.R.dimen.system_app_widget_background_radius` where it exists, so a widget is shaped like
every other widget on the launcher. No colour, size or corner is invented locally
([ADR 0007](adr/0007-design-system.md)).

## Reuse rather than reimplementation

Completing and snoozing a task from the widget runs `ReminderActionExecutor` — the same code the
notification actions use, so a snooze from a widget reschedules exactly like a snooze from the
shade, and a completion cancels the reminder notification. Deep links are built with
`StudyFlowDeepLinks.uriFor(...)`, never with a string route
([navigation](navigation.md)).
