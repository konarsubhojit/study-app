# 11. Alarm-style reminders get a full-screen UI, their own playback service, and a snooze cap

- Status: accepted
- Date: 2026-09-17

## Context

ADR 0004 already lets a reminder ask for `ReminderPrecision.ALARM` and resolves it to
`ReminderDelivery.ALARM_CLOCK`, and `AndroidReminderPlatformScheduler` already calls
`AlarmManager.setAlarmClock()` for it. But "wake me for the 09:00 exam" was never actually different
from a normal reminder once it fired: `ReminderDeliveryCoordinator` posted the same heads-up
notification either way, at whatever volume the channel happened to have, once, until it timed out
on its own. A silent or missed heads-up notification is exactly the failure this precision level
exists to rule out.

Closing that gap touches five different platform concerns at once, each with its own failure mode:

- **Reaching the user with the screen off and locked.** A heads-up notification can be swiped away
  half-asleep without registering. Only a full-screen `Activity` launched via a
  `fullScreenIntent` — already supported by `StudyFlowNotificationFactory.alert()` and already
  gated to the `ALARMS` channel — reliably takes over the screen. Android 14 lets the user (or the
  OS) revoke `USE_FULL_SCREEN_INTENT` at any time, so the fallback path has to be live, not a
  one-time check.
- **Being heard.** A channel's default sound is whatever the notification channel importance says,
  which is not "escalating alarm volume," survives no vibration pattern, and is not on
  `STREAM_ALARM`, so it can be silenced by the ringer/media volume the user last touched, and can be
  ducked by another app's audio focus.
- **Do Not Disturb.** DND does not silence `STREAM_ALARM` by OS design (the same channel Android's
  own Clock app uses), which is exactly the property that makes this precision level worth having —
  but a user who does not know that will be confused the first time an "alarm" reminder rings
  through a DND session they assumed was total silence.
- **Snooze, from a locked screen, without opening a door for infinite deferral.** The lock-screen
  buttons must work without unlocking the device (`ReminderActionExecutor` already models
  `SnoozeState`), but an alarm nobody has to actually address is worse than the missed exam it was
  meant to prevent.
- **Stopping reliably.** Whatever plays the sound must stop on dismiss, on timeout, and when the
  underlying task is completed from anywhere else in the app — not just from the alarm UI itself.

## Decision

**`ReminderDeliveryCoordinator` branches on `reminder.mode == ReminderMode.ALARM`.** An alarm-mode
reminder is posted to the `ALARMS` channel with only Dismiss and Snooze actions (Complete and
Start-session are dropped — the point of this screen is "stop ringing," not full task management),
attaches a `fullScreenIntent` to the new `AlarmActivity` only when
`SchedulingCapabilitiesProvider.currentCapabilities().canUseFullScreenIntent` is true — read fresh
on every delivery, per ADR 0004's degradation-is-reported principle, not cached — and otherwise
falls back to the same high-importance heads-up alert every other reminder gets. It also starts
`AlarmPlaybackService` right after the notification posts, and is exempted from the digest-silencing
rule: "wake me for the exam" is not the nudge the digest was built to replace.

**A foreground `AlarmPlaybackService`, not an Activity-owned player, owns the sound.** The service
must keep ringing across the `Activity`'s own recreation or destruction (the user backing out, the
OS killing it for memory), and must be stoppable from *outside* the alarm UI entirely — e.g.
marking the task done from the task list while the alarm rings. A `Service` that itself observes
`TaskRepository.observeTask(taskId)` and stops when the task is completed, deleted, or the reminder
is removed satisfies "stops on task completion from anywhere" without the `Activity` having to be
alive to notice.

**Playback: `STREAM_ALARM`, audio focus, escalating volume, vibration, a timeout.** The
`MediaPlayer` uses `AudioAttributes.USAGE_ALARM` / `CONTENT_TYPE_SONIFICATION` (which routes to
`STREAM_ALARM` and is what lets it play through DND) and requests
`AUDIOFOCUS_GAIN_TRANSIENT`, so it doesn't fight with music or an in-progress call and yields
control back on stop. `AlarmPlaybackPolicy` is a pure, Android-free object (no `SystemClock`,
tested without Robolectric) that ramps volume from audible to full over `ESCALATION_DURATION` (20s)
— loud enough to notice immediately, not so loud it startles — and defines a `VIBRATION_PATTERN`
and a `TIMEOUT_DURATION` (2 minutes) after which the alarm stops itself. Chosen ringtone is read
from `UserSettings.alarm_ringtone_uri` (empty string = system default alarm sound, resolved via
`RingtoneManager` at playback time so a later change to the system default is picked up
automatically without StudyFlow needing to react to it).

**Timeout auto-snoozes rather than silently dismissing.** `AlarmPlaybackPolicy.OUTCOME_AFTER_TIMEOUT
= ReminderActionKind.SNOOZE`: an alarm nobody heard deserves another attempt, not one where the app
quietly gives up. The snooze cap (below) still bounds how many times this can happen.

**Snooze is capped at `MAX_SNOOZE_COUNT = 3` in `ReminderActionExecutor`.** Before this issue,
snoozing an ordinary heads-up notification cost a deliberate tap, which is its own limiter. A
full-screen alarm's Snooze button is reachable from a locked screen with a single tap and no
context, which makes an *uncapped* snooze actively dangerous — the device can ring, get reflexively
snoozed, and never actually wake anyone. Once `SnoozeState.count` reaches the cap, a further snooze
request is treated as a dismiss (silences the alarm, does not reschedule, does not mark the task
complete) rather than silently refused or granted anyway — the user (or the timeout) still gets a
clear, inspectable outcome instead of an alarm that mysteriously stops responding to its own button.

**`ReminderActionKind.DISMISS` is new and distinct from `COMPLETE`.** Dismiss means "I heard the
alarm"; it does not mean "I did the task." It clears the notification and stops
`AlarmPlaybackService` without completing the task or touching its snooze state. Every terminal
action — Complete, a successful Snooze, a capped Snooze (now a Dismiss), Start-session, and Dismiss
itself — stops any ringing `AlarmPlaybackService` for that notification id, which is what makes
"stops on task completion from anywhere" true regardless of which surface the user acted from.

**Snooze and Dismiss work over the keyguard without dismissing it.** `AlarmActivity` uses
`setShowWhenLocked(true)` / `setTurnScreenOn(true)` (API 27+, with the legacy `WindowManager` flags
below it) and deliberately never calls `KeyguardManager.requestDismissKeyguard` — the buttons must
work *over* the lock screen, not unlock the device as a side effect of being pressed. Both buttons
broadcast to the existing `ReminderActionReceiver`, so the full-screen UI, the notification's own
actions, and (implicitly) any future surface all go through the one capped, idempotent executor.

**DND copy lives in `feature/settings`, not `feature/tasks`.** The issue pointed at
`ExactAlarmPermissionRationale`/`ReducedPrecisionBanner`
(`core/scheduling/ReminderSchedulingService.kt`) and the tasks reminder UI as candidate homes.
Investigation found `feature/tasks` has no UI yet for choosing `ReminderPrecision.ALARM` at all —
reminders are created through `ReminderPreset`, which only offers lead-time presets and always
defaults to `GENTLE`. Adding that picker is out of this issue's surgical scope (see Consequences).
The DND explanation and the new "Choose alarm sound" ringtone picker are instead surfaced on the
`ALARMS` channel's card in `NotificationSettingsScreen` — the smallest place that already shows the
user alarm-channel state, and the one place guaranteed to apply regardless of how a reminder later
becomes alarm-precision.

**Ringtone choice is a narrow interface, not the whole settings store.** `AlarmRingtoneSettings`
(`:core:datastore`) exposes only `uri: Flow<String>` and `suspend fun setUri(uri: String)`, backed by
`UserSettingsAlarmRingtoneSettings` wrapping the real `UserSettingsStore`. `settings.proto` gained
field 17, `alarm_ringtone_uri`, following the existing pattern of never reusing a number from
`reserved` (precedent: field 16, `digest_enabled`). The narrow interface — rather than injecting
`UserSettingsStore` itself — is what lets `NotificationSettingsViewModel`'s test fake the dependency
without a real DataStore or the generated proto type on its test classpath.

**Boot rescheduling gets an actual receiver.** ADR 0004 already asserted "alarms are re-armed
aggressively... on reboot," but no `BroadcastReceiver` for `ACTION_BOOT_COMPLETED` existed anywhere
in the codebase — the aspiration had outpaced the implementation. `BootRescheduleReceiver` (new)
listens for `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`, reads every task via
`TaskRepository`, and calls the existing `ReminderSchedulingService.rescheduleAll()` inside
`goAsync()`, using the same Hilt `EntryPointAccessors` pattern `ReminderActionReceiver` already uses
(a `BroadcastReceiver` cannot be `@AndroidEntryPoint`). This closes the "survives reboot" half of the
snooze acceptance criterion along with every other precision level's reboot behaviour.

## Consequences

- A locked, screen-off device with the app never opened since reboot still rings, is dismissible,
  and is snoozable, without unlocking — the full-screen path, the boot receiver, and the
  keyguard-safe buttons together are what make this true end to end.
- Alarm audio stops reliably on dismiss, on timeout, and on task completion from *any* surface,
  because `AlarmPlaybackService` both reacts to every terminal action in `ReminderActionExecutor`
  and independently watches the task itself.
- Snooze is bounded: at most `MAX_SNOOZE_COUNT` (3) deferrals per reminder before further presses
  become a dismiss instead, avoiding the "rings forever, never actually escalates" failure mode a
  lock-screen-reachable Snooze button would otherwise invite.
- DND will not silence an alarm-style reminder, by design, and the settings screen now says so in
  the one place a user is looking at that channel's behaviour — but a user who never opens
  Settings still won't see the explanation until this issue's follow-up gives `feature/tasks` its
  own alarm-precision picker with inline copy at the point of creation.
- `feature/tasks` still cannot let a user *choose* `ReminderPrecision.ALARM` when creating a
  reminder; this ADR's scope is what happens once a reminder *is* alarm-precision (already possible
  via `ReminderPrecision` today, just not exposed as UI), not adding that picker. That is a known,
  explicitly out-of-scope gap for a follow-up.
- `AlarmActivity` is plain Android `View`s, not Compose — `:core:scheduling` had no Compose
  dependency and this issue did not need one; a future full redesign of this screen may want to
  reconsider that if the screen grows more visual complexity than "title, due time, two buttons."
- The full lock-screen/screen-off behaviour cannot be verified by a JVM/Robolectric unit test; it is
  validated by code review against documented Android APIs (`setShowWhenLocked`,
  `FLAG_ACTIVITY_NEW_TASK`, `fullScreenIntent`) and left for manual/emulator verification before
  release, same as the rest of this codebase's lock-screen-adjacent behaviour.
