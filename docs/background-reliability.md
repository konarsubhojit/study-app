# Background work and battery reliability

This page is the implementation inventory and device-validation record for background execution.
Periodic WorkManager work is best-effort: its interval is a minimum cadence, not a delivery-time
promise. Android, Doze, standby buckets, battery restrictions, and OEM policy can defer work.

## Background-work inventory

| Mechanism | Why it exists | Expected frequency and constraints |
|---|---|---|
| Timer foreground service (`TimerForegroundService`) | Keeps a user-started running or paused study session visible and controllable; Android renders the notification chronometer. | Only while a session is active/paused. No timer tick loop or wake lock. |
| Timer interval alarms (`TimerIntervalCoordinator`) | Focus/break completion, inactivity prompt, and runaway-session guard. | One-shot elapsed-realtime alarms for configured session boundaries; up to three while running, replaced/cancelled on state changes. Exact-while-idle where allowed, otherwise inexact-while-idle. Not a per-second timer. |
| Reminder `AlarmManager` alarms (`AndroidReminderPlatformScheduler`) | Delivers user-created exact and alarm-clock reminders. | One-shot per outstanding reminder. Exact reminders use `setExactAndAllowWhileIdle`; alarm-style reminders use `setAlarmClock`. If exact-alarm access is denied, delivery degrades to inexact WorkManager work and the app reports the degradation. |
| Reminder WorkManager work (`ReminderDeliveryWorker`) | Delivers gentle/inexact reminders. | One-shot, one unique request per reminder, delayed until its next trigger. WorkManager may defer it, especially in Doze or restricted standby. |
| Reminder integrity (`ReminderIntegrityWorker`) | Reconciles persisted reminders with platform alarms and catches up overdue reminders. | Periodic, 24-hour interval, plus an immediate check at app startup and reconciliation after boot, app update, clock change, and timezone change. |
| Daily task digest (`DigestNotificationWorker`) | Posts one consolidated “tasks due today” notification instead of one ping per task. | Periodic, 24-hour interval, installed at app startup. A disabled digest is a fast no-op; individual reminders remain in effect. |
| Session sync (`SyncWorker`) | Pulls changes on read-only devices and drains queued session changes. | Periodic catch-up every 6 hours, requiring a connected network; also one-time work after relevant mutations or a manual sync. Unique work coalesces triggers; retries use exponential backoff. |
| Material remote verification (`MaterialRemoteVerificationWorker`) | Verifies that uploaded objects still exist remotely and repairs missing copies. | One-time per app cold start, unique/`KEEP`, and requires a connected network. |
| Material uploads (`MaterialUploadWorker`) | Resumable upload of user-selected material. | One-time per material, requires a connected network and battery-not-low, with exponential retry. Promotes to a data-sync foreground notification only while transferring. |
| Material downloads (`MaterialDownloadWorker`) | Downloads user-requested material for local use. | One-time per material, requires a connected network and battery-not-low. |
| Weekly summary (`WeeklySummaryWorker`) | Sends the user's opted-in weekly study recap. | One periodic request every 7 days, aligned to the chosen local weekday/time. Off by default and cancelled when disabled. |
| Alarm playback service (`AlarmPlaybackService`) | Plays/vibrates for a fired alarm-style reminder and stops independently of the alarm screen. | Only while an alarm is actively ringing; auto-stops after 2 minutes. The volume ramp checks every 500 ms for at most its first 20 seconds, then sleeps until timeout (no repeated polling during the remaining interval). |
| Boot/time-change receivers | Recover the timer and re-arm alarms whose registrations were lost or whose wall-clock target moved. | System broadcasts only; no periodic polling. |
| Widgets and Quick Settings tile | Display or let the user control timer/task state. | System/user initiated; widgets declare no periodic update. |

There are three foreground execution paths: the session timer (`specialUse` on Android 14+), alarm
audio (`mediaPlayback`), and active material upload (`dataSync`, through WorkManager's
`SystemForegroundService`). The timer service exists only for the user-started session; alarm
playback is bounded to two minutes; upload foreground work is tied to a transfer. The currently
configured target SDK is 37. Validate the applicable Android foreground-service start/type and
time-limit rules on the target OS before release; do not assume that a foreground service bypasses
those rules. Android 15+ applies a cumulative six-hour-per-24-hour background limit to `dataSync`
foreground services. Uploads are resumable, but the WorkManager-managed foreground service's timeout
and continuation behavior still needs a long-transfer test on a target-API device. See the [official
foreground-service timeout documentation](https://developer.android.com/develop/background-work/services/fgs/timeout).

The code audit found no direct `PowerManager.WakeLock` acquisition, no `JobScheduler` use outside
WorkManager, and no timer-display polling. Android or WorkManager may hold a temporary system
wake-lock while delivering an alarm or running eligible work; battery traces must identify its owner
and tie its duration to an inventory item rather than treating all platform-managed locks as an app
lock leak. Network work is either user/mutation-triggered or constraint-backed; there is no
recurring network poll faster than the six-hour sync catch-up. The bounded alarm-volume ramp is the
only periodic coroutine tick found in the audit.

## Restricted-state behavior and user mitigation

- **Doze:** the timer's displayed elapsed time continues because it is rendered by the notification
  chronometer, not advanced by the app. Timer-boundary alarms and exact reminder alarms request
  allow-while-idle delivery. Gentle reminders and WorkManager tasks can be batched or delayed.
- **Standby buckets:** daily/weekly WorkManager jobs and connected-network work remain best-effort
  and may be deferred, increasingly so in `RARE` or `RESTRICTED`. Exact alarms have the strongest
  path available to StudyFlow but are still subject to OS permission and device policy; do not
  promise an exact delivery time in the restricted bucket.
- **Background-restricted app:** treat all background delivery as potentially delayed or blocked.
  A service or alarm is not a general exemption from Android's background restrictions. Re-open
  StudyFlow to allow persisted reminders to be reconciled; then review the system's battery and
  exact-alarm settings.
- **User-facing mitigation already present (#50):** Settings → Notifications → Battery diagnostics
  reports battery optimization and the standby bucket, shows manufacturer-specific guidance, and
  links to battery settings. Its restricted-state copy warns that gentle reminders may be delayed by
  hours or until the app is opened. For stronger punctuality, use an alarm-style reminder where
  available and allow exact alarms; notifications must also be enabled. OEM-specific background
  controls may need a separate allowlist setting.

The diagnostic snapshot reads battery-optimization status and the standby bucket; it does not
measure alarm latency or detect every OEM's background-restriction policy. The guidance is a
mitigation, not a guarantee. The app reports exact-alarm and notification degradation rather than
silently treating fallback delivery as punctual.

### Manual/emulator scenarios

Use a debuggable build and a dedicated device/emulator. Replace `PACKAGE` with the installed
application id (mock builds use the `.mock` suffix). Record OS/API, build, package, battery
optimization state, standby bucket, exact-alarm permission, notification permission, network state,
and whether the test is on AOSP or an OEM build.

```sh
# Force and leave Doze.
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce

# Exercise standby buckets (Android 9+); reopen the app between scenarios if needed.
adb shell am set-standby-bucket PACKAGE active
adb shell am set-standby-bucket PACKAGE working_set
adb shell am set-standby-bucket PACKAGE frequent
adb shell am set-standby-bucket PACKAGE rare
adb shell am set-standby-bucket PACKAGE restricted

# Inspect pending platform work and battery attribution.
adb shell dumpsys alarm
adb shell dumpsys jobscheduler
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats
```

Also exercise **Settings → Apps → StudyFlow → App battery usage → Restricted** (wording varies by
Android/OEM), since a standby-bucket command is not equivalent to the user-enabled background
restriction. Under each state, verify: timer notification remains visible and chronometer advances;
timer pause/stop controls work; a due exact/alarm-clock reminder, a gentle reminder, and a timer
boundary each produce the documented outcome; a queued sync/upload waits for network; returning to
the app reconciles overdue reminders; and the diagnostic screen displays the observed bucket and
settings link. Record expected deferral rather than marking a delayed gentle reminder as a failure.

## Battery measurement protocol and baseline record

The development sandbox has no Android device/`adb`, so it has **no measured battery baseline**.
Do not interpret the targets below as results. Record measurements on representative physical
devices before claiming the battery acceptance criterion is met.

Use Battery Historian or `dumpsys batterystats` on a fully charged, unplugged device. Hold device,
OS/build, battery health, brightness, radios, network, room temperature, app data, and interaction
pattern constant. Run at least three paired samples per mode: a StudyFlow run and a matched control
run of the same duration and screen/radio use without StudyFlow's timer/background work. Report
median app-attributable delta as a percentage of rated battery capacity (or mAh where the device
exposes a defensible estimate), not the whole-device drain alone.

1. **Two-hour study:** reset battery stats; start a session, leave its notification visible, and
   follow the same screen-on/off and interaction schedule in the control run. Capture battery
   history plus `dumpsys batterystats` after two hours. Attribute CPU, partial wakelocks, alarms,
   network bytes/wakeups, and foreground-service time to StudyFlow.
2. **Idle day with reminders pending:** seed the same small, documented set of future exact and
   gentle reminders; screen off for 24 hours with the normal network state. Repeat with a matched
   no-reminder control. Capture before/after stats and inspect pending alarms/jobs. Do not count
   expected reminder delivery itself as an unexplained wakeup.

**Proposed release budgets (app-attributable delta, not total device drain):**

| Scenario | Budget | Required evidence |
|---|---:|---|
| Two-hour running study session | ≤1.0 percentage point of rated battery capacity | Paired median; one ongoing timer notification/FGS; no app-acquired wake lock, periodic timer wakeup, or timer-related network traffic. |
| 24-hour idle with reminders pending | ≤0.5 percentage point of rated battery capacity | Paired median; all pending work mapped to the inventory above; no app-acquired wake lock or unexpected network wakeup; report any bounded platform/WorkManager wake-lock time. |

These are budgets to validate, not measured baselines. If the device's battery attribution resolution
cannot distinguish a result from the control/noise floor, report the raw readings and classify the
result as inconclusive rather than rounding it to a pass.

| Device / OS / build | Scenario | Samples | StudyFlow drain | Control drain | Attributable delta | Wake locks / alarms / network | Result |
|---|---|---:|---:|---:|---:|---|---|
| Not measured in this environment | 2-hour study | — | — | — | — | — | Pending physical-device run |
| Not measured in this environment | 24-hour idle + reminders | — | — | — | — | — | Pending physical-device run |

### Review checklist

- [x] Source audit: no app-acquired wake locks; timer display uses the platform chronometer.
- [x] Source audit: all periodic WorkManager requests have a named purpose and documented interval.
- [x] Timer interval alarms are one-shot session boundaries, not a per-second tick.
- [ ] Capture and review Battery Historian/`dumpsys batterystats` baselines on physical devices.
- [ ] Run Doze, all standby buckets (including `RESTRICTED`), and user-enabled background-restricted scenarios on supported Android versions/OEMs.
- [ ] Attach recorded measurements and observed reminder latencies before marking the battery/restricted-state acceptance criteria complete.
