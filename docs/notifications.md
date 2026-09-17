# Notifications

One notification layer, in `:core:notifications`, used by the timer, reminders and uploads. A
feature never touches `NotificationManager` directly: it names a channel from
`StudyFlowNotificationChannel`, builds the notification with `StudyFlowNotificationFactory` and
posts it through `StudyFlowNotifier`, which returns what actually happened.

## Channels

Every notification the app posts belongs to one of four documented channels. The importance below
is the app's *default*; once the channel exists the user owns it, and registration never overwrites
their choice.

| Channel | Group | Default importance | Shape | Why |
|---|---|---|---|---|
| Study timer (`studyflow.channel.study_timer`) | Focus | Low | ongoing chronometer, no badge | the running session must stay visible and never make a sound |
| Task reminders (`studyflow.channel.task_reminders`) | Reminders | Default | dismissible alert | the user asked to be told; a heads-up peek is proportionate |
| Alarms (`studyflow.channel.alarms`) | Reminders | High | alert + full-screen intent | "wake me for the exam" — the only channel allowed to take over the screen |
| Uploads (`studyflow.channel.uploads`) | Background | Min | progress, silent, no badge | progress the user can watch if they care, and ignore if they do not |

`NotificationChannelRegistrar` creates the groups and channels at startup (from
`AppStartupInitializer`) and again before posting if a channel has gone missing — a notification
sent to a deleted channel is dropped by the system with no error at all. Renaming a channel means a
new id plus a `legacyChannelIds` entry; that is the only sanctioned way for the app to delete one.

Per-channel and app-level system settings are one tap away: `channelSettingsIntent(channel)` and
`appSettingsIntent()`.

## The `POST_NOTIFICATIONS` permission

Asked for at a **moment of value**, never at cold start. `NotificationMoment` enumerates the
moments, and `NotificationMoment.APP_LAUNCH` never prompts — that rule is a unit test rather than a
convention to remember.

`NotificationPermissionPolicy.actionFor(state, moment)` is the single decision:

| State | Action |
|---|---|
| can post | nothing |
| never asked | show the system dialog |
| denied once (`shouldShowRequestPermissionRationale`) | explain, then ask again |
| permanently denied | send to system settings and say what is lost |
| granted, but notifications switched off | send to system settings and say what is lost |
| below Android 13, notifications switched off | send to system settings and say what is lost |

Android cannot say whether the dialog has ever been shown — `shouldShowRequestPermissionRationale`
is `false` both before the first request and after a permanent denial — so
`NotificationPermissionRequestLog` records it. It is permission bookkeeping, not a user preference,
which is why it is not in the Proto DataStore.

## Denied states degrade visibly

`StudyFlowNotifier.post` returns `POSTED`, `PERMISSION_DENIED` or `CHANNEL_DISABLED`; it never
throws (a `SecurityException` from a permission revoked mid-flight is reported as a denial) and
never silently does nothing. Each `NotificationMoment` names the message key describing what the
user loses, so a denial is a sentence on screen:

| Channel off | What still works |
|---|---|
| Study timer | the session keeps running; the timer is visible inside the app |
| Task reminders | reminders are still stored and shown in the app |
| Alarms | the alarm is scheduled but cannot ring or take over the screen |
| Uploads | uploads still finish; progress and failures are invisible |

## In-app settings screen

`:feature:settings` renders `NotificationSettingsRoute`: the four channels, whether each is
currently allowed, and a shortcut to the system page that can change it. It re-reads system state
on every resume, because both of its buttons lead to system settings and a stale screen would look
broken exactly when the user had just fixed something. It only ever mirrors the system — since
Android 8 nothing but system settings can turn a channel back on.

## PendingIntent audit

Every `PendingIntent` is created by `StudyFlowPendingIntents` and is `FLAG_IMMUTABLE or
FLAG_UPDATE_CURRENT`. There is deliberately no helper that produces a mutable one, so the audit is
`grep FLAG_MUTABLE` finding nothing. StudyFlow needs none of the features that require mutability
(direct reply, bubbles, slices).

- `activity(deepLink)` — an explicit `ACTION_VIEW` of a `studyflow://` URI, built with
  `StudyFlowDeepLinks.uriFor` (see [navigation](navigation.md)). The URI is the intent's data, so
  two destinations never collapse onto one `PendingIntent`.
- `broadcast(intent)` / `service(intent)` — action buttons. Both reject an implicit intent, because
  an implicit broadcast from a notification action would be readable by every app on the device.

## Study timer foreground service

The running or paused timer is kept visible by `TimerForegroundService`. It is declared as the
special-use foreground service "Ongoing study-session timer notification controls" because its only
job is to keep a user-started study session visible and controllable outside the app. The service
posts a single ongoing notification: while the timer is running, `setUsesChronometer` lets the
platform render the ticking clock from the persisted timer state; while paused, the notification
stays controllable but disables the chronometer so the app does no paused work.

Timer controls are service `PendingIntent`s (pause/resume and stop) so they still execute after the
app process has died. Opening the notification uses `StudyFlowDeepLinks.uriFor(TimerRoute(...))` to
return to the running-timer screen.
